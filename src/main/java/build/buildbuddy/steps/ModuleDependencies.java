package build.buildbuddy.steps;

import build.buildbuddy.BuildStep;
import build.buildbuddy.BuildStepArgument;
import build.buildbuddy.BuildStepContext;
import build.buildbuddy.BuildStepResult;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.DirectiveTree;
import com.sun.source.tree.RequiresTree;
import com.sun.source.util.JavacTask;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

public class ModuleDependencies implements BuildStep {

    public static final String MODULES = "modules/";

    private final Function<String, String> resolver;
    private final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

    public ModuleDependencies(Function<String, String> resolver) {
        this.resolver = resolver;
    }

    @Override
    public CompletionStage<BuildStepResult> apply(Executor executor,
                                                  BuildStepContext context,
                                                  Map<String, BuildStepArgument> arguments) throws IOException {
        Map<String, String> references = new HashMap<>();
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
            Path modules = entry.getValue().folder().resolve(MODULES + "modules.properties");
            if (Files.exists(modules)) {
                Properties properties = new Properties();
                try (Reader reader = Files.newBufferedReader(modules)) {
                    properties.load(reader);
                }
                for (String module : properties.stringPropertyNames()) {
                    references.put(module, resolver.apply(properties.getProperty(module)));
                }
            }
        }
        Path dependencies = Files.createDirectory(context.next().resolve(PropertyDependencies.DEPENDENCIES));
        for (Map.Entry<String, BuildStepArgument> entry : arguments.entrySet()) {
            Path moduleInfo = entry.getValue().folder().resolve(Resolve.SOURCES + "module-info.java");
            if (Files.exists(moduleInfo)) {
                Properties properties = new Properties();
                JavacTask javac = (JavacTask) compiler.getTask(new PrintWriter(Writer.nullWriter()),
                        compiler.getStandardFileManager(null, null, null),
                        null,
                        null,
                        null,
                        List.of(new SimpleJavaFileObject(moduleInfo.toUri(), JavaFileObject.Kind.SOURCE) {
                            @Override
                            public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                                return Files.readString(moduleInfo);
                            }
                        }));
                for (CompilationUnitTree unit : javac.parse()) {
                    for (DirectiveTree directive : requireNonNull(unit.getModule()).getDirectives()) {
                        if (directive instanceof RequiresTree requires) {
                            properties.put(Objects.requireNonNull(
                                    references.get(requires.getModuleName().toString()),
                                    "Unknown module: " + requires.getModuleName().toString()), "");
                        }
                    }
                }
                try (Writer writer = Files.newBufferedWriter(dependencies.resolve(entry.getKey() + ".properties"))) {
                    properties.store(writer, null);
                }
            }
        }
        return CompletableFuture.completedStage(new BuildStepResult(true));
    }
}
