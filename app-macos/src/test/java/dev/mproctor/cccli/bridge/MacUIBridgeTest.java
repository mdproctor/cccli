package dev.mproctor.cccli.bridge;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

/**
 * Unit tests for MacUIBridge.resolveDylibPath().
 *
 * MacUIBridge is a Quarkus @ApplicationScoped bean — instantiating it with
 * new MacUIBridge() bypasses CDI so @PostConstruct does not fire. This lets
 * us test the path-resolution logic in isolation without loading the dylib.
 */
class MacUIBridgeTest {

    private static final String PROP = "cccli.dylib.path";

    private MacUIBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new MacUIBridge();
        System.clearProperty(PROP); // always start clean
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(PROP);
    }

    // ── Tier 1: system property ───────────────────────────────────────────────

    @Test
    void systemPropertyReturnedDirectly() {
        System.setProperty(PROP, "/custom/path/libMyMacUI.dylib");
        assertEquals("/custom/path/libMyMacUI.dylib",
                bridge.resolveDylibPath("/any/exe/path"),
                "system property should take priority over everything else");
    }

    @Test
    void systemPropertyTakesPriorityOverBundle(@TempDir Path tempDir) throws IOException {
        // Even if a valid bundle structure exists, the property wins
        Path bundleDylib = createBundleStructure(tempDir);
        Path fakeExe = tempDir.resolve("Contents/MacOS/claude-desktop");

        System.setProperty(PROP, "/override/libMyMacUI.dylib");
        assertEquals("/override/libMyMacUI.dylib",
                bridge.resolveDylibPath(fakeExe.toString()));
    }

    // ── Tier 2: .app bundle detection ─────────────────────────────────────────

    @Test
    void detectsBundleDylibWhenStructureExists(@TempDir Path tempDir) throws IOException {
        Path bundleDylib = createBundleStructure(tempDir);
        Path fakeExe = tempDir.resolve("Contents/MacOS/claude-desktop");

        String result = bridge.resolveDylibPath(fakeExe.toString());
        assertEquals(bundleDylib.toAbsolutePath().toString(), result,
                "should detect and return the bundle-relative dylib path");
    }

    @Test
    void bundleDetectionUsesAbsolutePath(@TempDir Path tempDir) throws IOException {
        createBundleStructure(tempDir);
        Path fakeExe = tempDir.resolve("Contents/MacOS/claude-desktop");

        String result = bridge.resolveDylibPath(fakeExe.toString());
        assertTrue(Path.of(result).isAbsolute(), "returned bundle dylib path must be absolute");
    }

    // ── Tier 3: dev mode fallback ─────────────────────────────────────────────

    @Test
    void fallsBackToDefaultWhenNotInBundle() {
        // A JVM executable path — no Contents/Frameworks/libMyMacUI.dylib relative to it
        String result = bridge.resolveDylibPath("/usr/bin/java");
        assertEquals("../mac-ui-bridge/build/libMyMacUI.dylib", result,
                "should fall back to dev default when not inside a .app bundle");
    }

    @Test
    void fallsBackToDefaultWhenExecutablePathIsEmpty() {
        String result = bridge.resolveDylibPath("");
        assertEquals("../mac-ui-bridge/build/libMyMacUI.dylib", result,
                "empty executable path should fall back to dev default");
    }

    @Test
    void fallsBackToDefaultWhenDylibMissingFromBundle(@TempDir Path tempDir) throws IOException {
        // Create the directory structure but NOT the dylib file
        Files.createDirectories(tempDir.resolve("Contents/MacOS"));
        Files.createDirectories(tempDir.resolve("Contents/Frameworks"));
        Path fakeExe = tempDir.resolve("Contents/MacOS/claude-desktop");
        Files.createFile(fakeExe);

        String result = bridge.resolveDylibPath(fakeExe.toString());
        assertEquals("../mac-ui-bridge/build/libMyMacUI.dylib", result,
                "should fall back when Frameworks/libMyMacUI.dylib does not exist");
    }

    @Test
    void handlesPathWithSingleParent() {
        // Edge case: executable is directly in root (only one parent level)
        // Path.of("/claude-desktop").getParent() = "/" which has no further parent
        String result = bridge.resolveDylibPath("/claude-desktop");
        assertEquals("../mac-ui-bridge/build/libMyMacUI.dylib", result,
                "single-level path should not NPE — should fall back to default");
    }

    // ── Tier 4: native binding ────────────────────────────────────────────────

    @Test
    void isInBundle_returnsFalse_inJvmTestMode() {
        // Load the dylib so the Panama binding can be exercised.
        // The dylib is built during generate-sources (make in mac-ui-bridge/).
        Path dylib = Path.of("../mac-ui-bridge/build/libMyMacUI.dylib").toAbsolutePath();
        assumeTrue(Files.exists(dylib), "dylib not built — skipping native binding test");
        System.load(dylib.toString());

        // In JVM test mode there are no bundle Resources, so myui_is_bundle() returns 0.
        // This verifies the binding works and correctly detects non-bundle mode.
        assertFalse(bridge.isInBundle());
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Creates a valid .app bundle structure and returns the dylib path. */
    private Path createBundleStructure(Path root) throws IOException {
        Path macos      = root.resolve("Contents/MacOS");
        Path frameworks = root.resolve("Contents/Frameworks");
        Files.createDirectories(macos);
        Files.createDirectories(frameworks);
        Files.createFile(macos.resolve("claude-desktop"));
        Path dylib = frameworks.resolve("libMyMacUI.dylib");
        Files.createFile(dylib);
        return dylib;
    }
}
