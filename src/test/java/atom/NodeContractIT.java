package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The environment half of the node atom: where the installation is found, which shapes are accepted, and what the
 * outputs say when the installation is unusual (no npm, no version, a very long version line).
 *
 * <p>{@code NodeIT} covers the contract a caller relies on; the cases here are the ones that decide whether
 * {@code nodeHome}, {@code NODE_HOME} and a bare {@code PATH} lookup agree with each other.
 */
class NodeContractIT {

    private Atom atom;
    private Path node;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
        node = Files.createTempDirectory("atom-node-contract").resolve("node");
        install(node.resolve("bin"));
    }

    @AfterEach
    void tearDown() throws IOException {
        delete(node.getParent());
    }

    private static void install(Path bin) throws IOException {
        Files.createDirectories(bin);
        write(bin.resolve("node"), "#!/bin/sh\n"
                + "case \"$1\" in\n"
                + "  --version) echo \"v20.11.1\" ;;\n"
                + "  *) echo \"ran with node from $0\" ;;\n"
                + "esac\n");
        write(bin.resolve("npm"), "#!/bin/sh\necho \"10.2.4\"\n");
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        file.toFile().setExecutable(true);
    }

    private static void delete(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test
    @DisplayName("a nodeHome relative to the workspace is resolved against it, not against the process directory")
    void resolvesARelativeHomeInsideTheWorkspace() throws Exception {
        // bin() puts an executable node and npm in the workspace's bin/, so "bin" is a real relative installation
        AtomResult run = atom.bin("node", "#!/bin/sh\necho v20.11.1\n")
                .bin("npm", "#!/bin/sh\necho 10.2.4\n")
                .input("nodeHome", "bin")
                .run();

        run.assertOk();
        assertEquals(run.work().resolve("bin").toString(), run.field("NODE_HOME"),
                "a relative nodeHome must resolve inside the workspace:\n" + run.console());
        assertEquals("v20.11.1", run.field("NODE_VERSION"));
    }

    @Test
    @DisplayName("NODE_HOME is used when nodeHome is left alone, and reported back as an absolute path")
    void fallsBackToTheNodeHomeInput() throws Exception {
        AtomResult run = atom.input("NODE_HOME", node.toString())
                .input("prependPath", "false")
                .run();

        run.assertOk();
        assertEquals(node.toString(), run.field("NODE_HOME"));
        assertEquals("v20.11.1", run.field("NODE_VERSION"));
    }

    @Test
    @DisplayName("with neither input the installation is found on PATH, and PATH is emitted from the environment")
    void findsNodeOnThePath() throws Exception {
        // both halves of the fallback: no nodeHome/NODE_HOME, and the PATH input empty so the process PATH is used
        AtomResult run = atom.env("PATH", node.resolve("bin").toString())
                .input("PATH", "")
                .input("command", "echo found by path")
                .run();

        run.assertOk();
        assertEquals(node.resolve("bin").toAbsolutePath().toString(), run.field("NODE_HOME"),
                "the installation found on PATH was not reported:\n" + run.console());
        assertEquals("v20.11.1", run.field("NODE_VERSION"));
        assertEquals("10.2.4", run.field("npmVersion"));
        assertTrue(run.field("PATH").startsWith(node.resolve("bin").toAbsolutePath() + ":"),
                "the process PATH was not prefixed with the installation: " + run.field("PATH"));
        assertTrue(run.console().contains("found by path"), run.console());
    }

    @Test
    @DisplayName("an empty PATH means node is not found, and the message says what to set")
    void reportsAnEmptyPathClearly() throws Exception {
        AtomResult run = atom.env("PATH", "").input("PATH", "").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("node was not found on PATH"),
                "the message does not name the PATH lookup:\n" + run.file("output.json"));
        assertTrue(run.file("output.json").contains("nodeHome"),
                "the message does not say how to fix it:\n" + run.file("output.json"));
    }

    @Test
    @DisplayName("an empty PATH in the child environment still gets the bare installation directory, not ':...'")
    void prefixesABlankPathWithTheInstallationOnly() throws Exception {
        AtomResult run = atom.env("PATH", "")
                .input("PATH", "")
                .input("nodeHome", node.toString())
                .run();

        run.assertOk();
        assertEquals(node.resolve("bin").toString(), run.field("PATH"),
                "a blank PATH must become the installation directory alone: '" + run.field("PATH") + "'");
    }

    @Test
    @DisplayName("a node that prints no version is an environment error, not a silent success")
    void refusesAnInstallationWithoutAVersion() throws Exception {
        Path silent = Files.createTempDirectory("atom-node-silent").resolve("node");
        try {
            Files.createDirectories(silent.resolve("bin"));
            write(silent.resolve("bin/node"), "#!/bin/sh\nexit 0\n");
            write(silent.resolve("bin/npm"), "#!/bin/sh\nexit 0\n");

            AtomResult run = atom.input("nodeHome", silent.toString()).run();

            run.assertRefused();
            assertTrue(run.file("output.json").contains("produced nothing"),
                    "the refusal does not say the version was empty:\n" + run.file("output.json"));
        } finally {
            delete(silent.getParent());
        }
    }

    @Test
    @DisplayName("no npm in the installation is reported as absent instead of an empty version")
    void reportsAMissingNpm() throws Exception {
        Files.delete(node.resolve("bin/npm"));

        AtomResult run = atom.input("nodeHome", node.toString()).input("prependPath", "false").run();

        run.assertOk();
        assertEquals("", run.field("npmVersion"), "npm was removed, its version must be empty");
        assertTrue(run.console().contains("npm absent"), "the log does not say npm is absent:\n" + run.console());
        assertFalse(run.console().contains("npm 10.2.4"), run.console());
    }

    @Test
    @DisplayName("a version line longer than 80 characters is cut, so it stays one readable output field")
    void cutsAnOverlongVersionLine() throws Exception {
        Path chatty = Files.createTempDirectory("atom-node-chatty").resolve("node");
        try {
            Files.createDirectories(chatty.resolve("bin"));
            StringBuilder longVersion = new StringBuilder("v9.");
            longVersion.append("0".repeat(120));
            write(chatty.resolve("bin/node"), "#!/bin/sh\necho \"" + longVersion + "\"\n");

            AtomResult run = atom.input("nodeHome", chatty.toString()).input("prependPath", "false").run();

            run.assertOk();
            assertEquals(80, run.field("NODE_VERSION").length(),
                    "the version was not cut to 80 characters: " + run.field("NODE_VERSION"));
            assertEquals(longVersion.substring(0, 80), run.field("NODE_VERSION"));
        } finally {
            delete(chatty.getParent());
        }
    }
}
