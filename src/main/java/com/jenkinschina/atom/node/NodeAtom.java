package com.jenkinschina.atom.node;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * nodejs as an atom: put a Node installation on PATH for the steps that follow, and optionally run one command.
 *
 * <p>The runner exports outputs under their own name, so returning {@code PATH} and {@code NODE_HOME} is all that is
 * needed — the next atom sees a workspace where {@code node} works. No tool installer, no per-agent setup.
 */
public class NodeAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String home = context.getParam("nodeHome", "");
        if (home.isBlank()) {
            home = orEmpty(context.getParam("NODE_HOME"));
        }

        Path bin = resolveBin(home, workspace);
        if (bin == null) {
            return AtomResult.error(ErrorType.ENVIRONMENT, 40001, home.isBlank()
                    ? "node was not found on PATH; set nodeHome to the installation directory"
                    : "no node binary under " + home + " (looked in bin/)");
        }

        String nodeDir = bin.toString();
        // absolute paths, not a PATH lookup: the JDK resolves the program against the *parent* environment on macOS,
        // which silently picks up a different node than the one this atom was told to use
        String nodeBinary = bin.resolve("node").toString();
        String version = firstLine(exec(workspace, nodeDir, nodeBinary, "--version").output());
        if (version.isBlank()) {
            return AtomResult.error(ErrorType.ENVIRONMENT, 40002, "node --version produced nothing from " + nodeBinary);
        }
        java.nio.file.Path npmBinary = bin.resolve("npm");
        String npm = java.nio.file.Files.isExecutable(npmBinary)
                ? firstLine(exec(workspace, nodeDir, npmBinary.toString(), "--version").output())
                : "";
        context.log("[node] " + version + " (npm " + (npm.isBlank() ? "absent" : npm) + ") from " + nodeDir);

        String command = context.getParam("command", "");
        boolean failOnCommandError = context.getBooleanParam("failOnCommandError", true);
        int commandExit = 0;
        if (!command.isBlank()) {
            context.log("[node] $ " + command);
            Result ran = exec(workspace, nodeDir, "/bin/sh", "-c", command);
            for (String line : ran.output().split("\n")) {
                if (!line.isEmpty()) {
                    context.log("[node] " + line);
                }
            }
            commandExit = ran.code();
            if (commandExit != 0 && failOnCommandError) {
                // a failed npm test must fail the step: silence here is how a broken build looks green
                return AtomResult.error(ErrorType.USER, 40003,
                        "the command exited " + commandExit + ": " + command);
            }
        }

        AtomResult result = AtomResult.success()
                .data("exitCode", String.valueOf(commandExit))
                .data("NODE_HOME", home.isBlank() ? nodeDir : Path.of(home).toAbsolutePath().toString())
                .data("NODE_VERSION", version)
                .data("nodeVersion", version)
                .data("npmVersion", npm);
        if (context.getBooleanParam("prependPath", true)) {
            // input.json carries the build env, but Pipeline does not always get PATH into that map; the process
            // itself was started with the agent's environment, so that is the reliable fallback
            String path = orEmpty(context.getParam("PATH"));
            if (path.isBlank()) {
                path = orEmpty(System.getenv("PATH"));
            }
            String prefix = nodeDir;
            if (path.isBlank()) {
                result.data("PATH", prefix);
            } else if (!path.startsWith(prefix + ":")) {
                result.data("PATH", prefix + ":" + path);
            }
        }
        return result;
    }

    /** Accept both a node home and a bin directory; return the directory that holds the binary. */
    private static Path resolveBin(String home, Path workspace) {
        if (!home.isBlank()) {
            // "tools/node" means inside the workspace: the process is not started in the workspace
            Path candidate = Path.of(home).isAbsolute() ? Path.of(home) : workspace.resolve(home);
            if (Files.isExecutable(candidate.resolve("node"))) {
                return candidate.toAbsolutePath();
            }
            if (Files.isExecutable(candidate.resolve("bin/node"))) {
                return candidate.resolve("bin").toAbsolutePath();
            }
            return null;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String entry : path.split(":")) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve("node");
            if (Files.isExecutable(candidate)) {
                return Path.of(entry).toAbsolutePath();
            }
        }
        return null;
    }

    private static Result exec(Path workspace, String nodeDir, String... command) {
        try {
            List<String> argv = new ArrayList<>(List.of(command));
            ProcessBuilder builder = new ProcessBuilder(argv).directory(workspace.toFile()).redirectErrorStream(true);
            String path = builder.environment().getOrDefault("PATH", "");
            builder.environment().put("PATH", nodeDir + (path.isBlank() ? "" : ":" + path));
            builder.environment().put("NODE_HOME", nodeDir);
            Process process = builder.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            int code = process.waitFor();
            return new Result(code, out.toString().strip());
        } catch (Exception e) {
            return new Result(-1, String.valueOf(e));
        }
    }

    private record Result(int code, String output) {}

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String line = text.strip().lines().findFirst().orElse("");
        return line.length() > 80 ? line.substring(0, 80) : line.trim();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    @SuppressWarnings("unused")
    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
