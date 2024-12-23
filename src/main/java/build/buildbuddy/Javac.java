package build.buildbuddy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

public class Javac implements ProcessBuildStep {

    private final String javac;

    public Javac() {
        javac = ProcessBuildStep.ofJavaHome("bin/javac");
    }

    public Javac(String javac) {
        this.javac = javac;
    }

    @Override
    public CompletionStage<ProcessBuilder> process(Executor executor,
                                                   BuildStepContext context,
                                                   Map<String, BuildStepArgument> arguments) {
        List<String> commands = new ArrayList<>(List.of(
                javac,
                "--release", Integer.toString(Runtime.version().version().getFirst()),
                "-d", context.next().toString()
        ));
        arguments.values().stream().flatMap(result -> result.files().keySet().stream()
                .filter(path -> path.getFileName().toString().endsWith(".java"))
                .map(path -> result.folder().resolve(path).toString())).forEach(commands::add);
        return CompletableFuture.completedStage(new ProcessBuilder(commands));
    }
}
