package org.laeubi.osgi.jdkhttp.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.jdkhttp.runtime.dto.DTOConstants;
import org.osgi.service.jdkhttp.runtime.dto.RequestInfoDTO;
import org.osgi.service.jdkhttp.runtime.dto.ResourceDTO;
import org.osgi.service.jdkhttp.whiteboard.JdkHttpWhiteboardConstants;

import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that verify serving of bundle resources registered with
 * {@link JdkHttpWhiteboardConstants#JDK_HTTP_RESOURCE_PATTERN}, as well as
 * {@link org.osgi.service.jdkhttp.runtime.JdkHttpServerRuntime#calculateRequestInfoDTO(String)}.
 *
 * <p>
 * The system bundle does not support {@link Bundle#getEntry(String)}, so a
 * tiny real bundle jar containing test resources is installed and started
 * for each test.
 * </p>
 */
class WhiteboardResourceTest extends AbstractWhiteboardTest {

    private Bundle resourceBundle;

    @BeforeEach
    void installResourceBundle() throws Exception {
        Path jar = Files.createTempFile("jdkhttp-resource-test", ".jar");
        jar.toFile().deleteOnExit();

        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("Bundle-ManifestVersion", "2");
        attrs.putValue("Bundle-SymbolicName", "org.laeubi.osgi.jdkhttp.test.resources");
        attrs.putValue("Bundle-Version", "1.0.0");

        try (OutputStream fileOut = Files.newOutputStream(jar);
             JarOutputStream jarOut = new JarOutputStream(fileOut, manifest)) {
            writeEntry(jarOut, "webroot/hello.txt", "Hello Resource!");
            writeEntry(jarOut, "webroot/index.html", "<html>Index</html>");
        }

        resourceBundle = context.installBundle(jar.toUri().toString());
        resourceBundle.start();
    }

    @AfterEach
    void uninstallResourceBundle() throws Exception {
        if (resourceBundle != null) {
            resourceBundle.uninstall();
        }
    }

    private static void writeEntry(JarOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private static Dictionary<String, Object> resourceProps(String pattern, String prefix) {
        Dictionary<String, Object> props = new Hashtable<>();
        props.put(JdkHttpWhiteboardConstants.JDK_HTTP_RESOURCE_PATTERN, pattern);
        props.put(JdkHttpWhiteboardConstants.JDK_HTTP_RESOURCE_PREFIX, prefix);
        return props;
    }

    private BundleContext resourceBundleContext() {
        return resourceBundle.getBundleContext();
    }

    @Test
    void resourceServesExistingBundleEntry() throws Exception {
        resourceBundleContext().registerService(Object.class, new Object(), resourceProps("/res", "/webroot"));

        HttpResponse<String> response = get("/res/hello.txt");

        assertEquals(200, response.statusCode());
        assertEquals("Hello Resource!", response.body());
    }

    @Test
    void resourceDefaultsToIndexHtml() throws Exception {
        resourceBundleContext().registerService(Object.class, new Object(), resourceProps("/res", "/webroot"));

        HttpResponse<String> response = get("/res/");

        assertEquals(200, response.statusCode());
        assertEquals("<html>Index</html>", response.body());
    }

    @Test
    void resourceRespondsNotFoundForMissingEntry() throws Exception {
        resourceBundleContext().registerService(Object.class, new Object(), resourceProps("/res", "/webroot"));

        HttpResponse<String> response = get("/res/does-not-exist.txt");

        assertEquals(404, response.statusCode());
    }

    @Test
    void resourceWithInvalidPatternFails() {
        // A pattern not starting with "/" is invalid; the property must still
        // be present so the resource tracker's LDAP filter picks up the
        // service in the first place.
        resourceBundleContext().registerService(Object.class, new Object(), resourceProps("no-leading-slash", "/webroot"));

        var failed = whiteboard.getFailedResourceDTOs();
        assertEquals(1, failed.length);
        assertEquals(DTOConstants.FAILURE_REASON_VALIDATION_FAILED, failed[0].failureReason);
    }

    @Test
    void resourceIsRemovedAfterServiceUnregistration() throws Exception {
        ServiceRegistration<Object> reg = resourceBundleContext().registerService(
                Object.class, new Object(), resourceProps("/res", "/webroot"));

        assertEquals(200, get("/res/hello.txt").statusCode());

        reg.unregister();

        assertEquals(0, whiteboard.getResourceDTOs().length);
        assertEquals(404, get("/res/hello.txt").statusCode());
    }

    @Test
    void calculateRequestInfoDTOMatchesResource() {
        resourceBundleContext().registerService(Object.class, new Object(), resourceProps("/res", "/webroot"));

        RequestInfoDTO info = whiteboard.calculateRequestInfoDTO("/res/hello.txt");

        assertEquals("/res/hello.txt", info.path);
        assertNotNull(info.resourceDTO);
        assertNull(info.handlerDTO);
        ResourceDTO resourceDTO = info.resourceDTO;
        assertEquals("/webroot", resourceDTO.prefix);
        assertTrue(java.util.Arrays.asList(resourceDTO.patterns).contains("/res"));
    }

    @Test
    void calculateRequestInfoDTOWithNoMatch() {
        RequestInfoDTO info = whiteboard.calculateRequestInfoDTO("/nowhere");

        assertEquals("/nowhere", info.path);
        assertNull(info.handlerDTO);
        assertNull(info.resourceDTO);
        assertNull(info.authenticatorDTO);
        assertEquals(0, info.filterDTOs.length);
    }
}
