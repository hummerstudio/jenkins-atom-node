package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The node atom, started the way the runner starts it.
 *
 * <p>What {@code tools/smoke.sh} checked: a fake Node installation proves the contract — {@code NODE_HOME},
 * {@code NODE_VERSION}, {@code npmVersion} and {@code PATH} come back as outputs, the command runs with them, a
 * missing installation and a failing command are both errors.
 */
class NodeIT {

    private Atom atom;
    private Path node;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
        // <tmp>/node is the installation, <tmp>/node/bin the bin directory — the two shapes the atom accepts
        node = Files.createTempDirectory("atom-node").resolve("node");
        install(node.resolve("bin"));
    }

    @AfterEach
    void tearDown() throws IOException {
        delete(node.getParent());
    }

    /** A node installation that answers {@code --version} and says where it came from for anything else. */
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
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    @DisplayName("NODE_HOME, NODE_VERSION, npmVersion and PATH come back, and the command runs with them")
    void putsNodeOnPath() throws Exception {
        AtomResult run = atom.input("nodeHome", node.toString())
                .input("command", "echo building with $NODE_HOME")
                .input("prependPath", "true")
                .run();

        run.assertOk();
        assertEquals("success", run.status(), run.console());
        assertEquals(node.toString(), run.field("NODE_HOME"));
        assertEquals("v20.11.1", run.field("NODE_VERSION"));
        assertEquals("10.2.4", run.field("npmVersion"));
        assertTrue(run.field("PATH").startsWith(node + "/bin:"),
                "PATH must start with the node directory: " + run.field("PATH"));
        assertTrue(run.console().contains("building with " + node),
                "the command did not see NODE_HOME:\n" + run.console());
    }

    @Test
    @DisplayName("a bin directory is accepted as well as a home, and prependPath=false does not emit PATH")
    void acceptsABinDirectory() throws Exception {
        AtomResult run = atom.input("nodeHome", node.resolve("bin").toString())
                .input("prependPath", "false")
                .run();

        run.assertOk();
        assertEquals("v20.11.1", run.field("NODE_VERSION"));
        assertEquals("", run.field("PATH"), "prependPath=false must not emit PATH");
    }

    @Test
    @DisplayName("a wrong directory is a clear error")
    void refusesAMissingInstallation() throws Exception {
        AtomResult run = atom.input("nodeHome", node.resolve("nonexistent/node").toString()).run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("no node binary"),
                "message not readable:\n" + run.document());
    }

    @Test
    @DisplayName("a failing command fails the atom, not silently")
    void failsOnACommandThatExitsNonZero() throws Exception {
        AtomResult run = atom.input("nodeHome", node.toString())
                .input("command", "echo boom; exit 3")
                .run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("exited 3"),
                "message:\n" + run.document());
    }

    @Test
    @DisplayName("unless the caller says otherwise: failOnCommandError=false keeps the exit code and goes on")
    void toleratesACommandErrorWhenAsked() throws Exception {
        AtomResult run = atom.input("nodeHome", node.toString())
                .input("command", "exit 3")
                .input("failOnCommandError", "false")
                .run();

        run.assertOk();
        assertEquals("3", run.field("exitCode"));
    }
}
