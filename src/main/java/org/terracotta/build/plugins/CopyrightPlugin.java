/*
 * Copyright Terracotta, Inc.
 * Copyright IBM Corp. 2024, 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.terracotta.build.plugins;

import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileTree;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JvmEcosystemPlugin;
import org.gradle.api.plugins.quality.Checkstyle;
import org.gradle.api.plugins.quality.CheckstylePlugin;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.resources.TextResource;
import org.gradle.api.services.ServiceReference;
import org.gradle.api.specs.Specs;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.SourceTask;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskCollection;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.util.PatternSet;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.gradle.process.ExecSpec;
import org.terracotta.build.services.Git;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoField;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.lang.Integer.parseInt;
import static org.gradle.language.base.plugins.LifecycleBasePlugin.VERIFICATION_GROUP;
import static org.terracotta.build.PluginUtils.capitalize;

/**
 * Copyright header enforcement plugin.
 * <p>
 * Ensure all source files have the correct copyright header by creating a bunch of correctly configured checkstyle
 * tasks, and gluing them all together using the right task dependencies.
 */
public class CopyrightPlugin implements Plugin<Project> {

  private static final String[] IGNORED_PATTERNS = new String[]{
          "**/*.jks", "**/*.cer", "**/*.csr",
          "**/*.json", "**/*.tson",
          "**/*.frs", "**/*.lck",
          "**/*.gz", "**/*.zip",
          "**/*.csv",
          "**/*.ico", "**/*.png", "**/*.gif",
          "**/license.xml"
  };

  @Override
  public void apply(Project project) {
    project.getPlugins().apply(CheckstylePlugin.class);

    TextResource copyrightConfig = project.getRootProject().getResources().getText().fromFile("config/checkstyle/copyright.xml");
    TaskCollection<CopyrightHeaderCheck> headerChecks = project.getTasks().withType(CopyrightHeaderCheck.class);
    headerChecks.configureEach(task -> {
      task.setConfig(copyrightConfig);
      task.setClasspath(project.files());
      task.getOutputs().upToDateWhen(Specs.satisfyAll());
    });
    TaskCollection<CopyrightUpdateCheck> updateChecks = project.getTasks().withType(CopyrightUpdateCheck.class);
    updateChecks.configureEach(task -> {
      task.getPatterns().convention(Arrays.asList(
              //<declaration><entity><years> or <declaration><years><entity>
              Pattern.compile("(?<declaration>[Cc]opyright(?:\\h+(?:\\([Cc]\\)|\\u00a9))?)\\h+(?<entity>.*?)\\h+(?<years>(?:(?:\\d{4}\\h*-\\h*)?\\d{4},\\h+)*(?:\\d{4}\\h*-\\h*)?(?<end>\\d{4}))"),
              Pattern.compile("(?<declaration>[Cc]opyright(?:\\h+(?:\\([Cc]\\)|\\u00a9))?)\\h+(?<years>(?:(?:\\d{4}\\h*-\\h*)?\\d{4},\\h+)*(?:\\d{4}\\h*-\\h*)?(?<end>\\d{4}))\\h+(?<entity>.*?)")
      ));
      task.getEndYear().convention(matcher -> parseInt(matcher.group("end")));
      task.getEntity().convention(matcher -> matcher.group("entity"));
    });

    TaskProvider<Task> centralTask = project.getTasks().register("copyright", task -> {
      task.setDescription("Run copyright enforcement");
      task.setGroup(VERIFICATION_GROUP);
      task.dependsOn(headerChecks, updateChecks);
    });

    project.getPlugins().withType(LifecycleBasePlugin.class).configureEach(plugin ->
            project.getTasks().named(LifecycleBasePlugin.CHECK_TASK_NAME, task -> task.dependsOn(centralTask))
    );

    Provider<File> buildDirectory = project.getLayout().getBuildDirectory().getAsFile();
    project.getPlugins().withType(JvmEcosystemPlugin.class).configureEach(plugin ->
            project.getExtensions().configure(SourceSetContainer.class, sourceSets -> sourceSets.configureEach(sourceSet ->
                    sourceSet.getExtensions().add("copyright", createCopyrightSet(project, sourceSet.getName(), copyright -> {
                      copyright.check(sourceSet.getAllSource().getSourceDirectories()
                              .filter(f -> !f.getAbsolutePath().startsWith(buildDirectory.get().getAbsolutePath())));
                    })))));

    project.getExtensions().add("copyright", createCopyrightSet(project, "", copyright -> {}));
  }

  public CopyrightExtension createCopyrightSet(Project project, String name, Action<CopyrightExtension> action) {
    CopyrightExtension copyrightExtension = new CopyrightExtension(project.getObjects());
    copyrightExtension.exclude(IGNORED_PATTERNS);

    project.getTasks().register("copyright" + capitalize(name) + "Header", CopyrightHeaderCheck.class, task -> {
      task.setSource(copyrightExtension.getFiles());
    });

    project.getTasks().register("copyright" + capitalize(name) + "Update", CopyrightUpdateCheck.class, task -> {
      task.setSource(copyrightExtension.getFiles());
      task.getOutputs().upToDateWhen(Specs.satisfyAll());
    });

    action.execute(copyrightExtension);

    return copyrightExtension;
  }

  public static class CopyrightExtension {

    private final ConfigurableFileCollection files;
    private final PatternSet patternSet;

    @Inject
    public CopyrightExtension(ObjectFactory objectFactory) {
      this.files = objectFactory.fileCollection();
      this.patternSet = objectFactory.newInstance(PatternSet.class);
    }

    public FileTree getFiles() {
      return files.getAsFileTree().matching(patternSet);
    }

    public void check(Object ... sources) {
      files.from(sources);
    }

    public void exclude(String ... excludes) {
      patternSet.exclude(excludes);
    }
  }

  public static abstract class CopyrightHeaderCheck extends Checkstyle {}

  public static abstract class CopyrightUpdateCheck extends SourceTask {

    @ServiceReference("git")
    public abstract Property<Git> getGit();

    @Input
    public abstract ListProperty<Pattern> getPatterns();

    @Input @Optional
    public abstract Property<Pattern> getExpectedEntity();

    @Internal
    public abstract Property<ToIntFunction<Matcher>> getEndYear();

    @Internal
    public abstract Property<Function<Matcher, String>> getEntity();


    private static final Pattern PORCELAIN_Z_STATUS_LINE = Pattern.compile("[ MTADRCU?]{2} (?<file>[^\u0000]+)(?:\u0000(?![ MTADRCU?]{2} )(?<from>[^\u0000]+))?\u0000");
    private static final Pattern BASE_BRANCH = Pattern.compile("main|release/\\d+(\\.\\d+)*");
    private static final String REMOTES_PREFIX = "refs/remotes/";

    private static Stream<MatchResult> matches(Pattern pattern, CharSequence input) {
      Stream.Builder<MatchResult> builder = Stream.builder();
      Matcher matcher = pattern.matcher(input);
      while (matcher.find()) {
        builder.accept(matcher.toMatchResult());
      }
      return builder.build();
    }

    @TaskAction
    public void checkModifiedCopyrights() {
      Set<File> sourceFiles = getSource().getFiles();

      Path root = sourceFiles.stream().map(File::toPath).reduce((a, b) -> {
        while (!b.startsWith(a)) {
          a = a.getParent();
        }
        return a;
      }).orElseThrow(GradleException::new);

      List<String> baseRefs = Stream.of(git(spec -> spec.args("for-each-ref", "--format=%(refname)", REMOTES_PREFIX)).split("\\R"))
          .filter(ref -> !ref.isBlank())
          .filter(ref -> {
            String remaining = ref.substring(REMOTES_PREFIX.length());
            String branch = remaining.substring(remaining.indexOf('/') + 1);
            return BASE_BRANCH.matcher(branch).matches();
          }).toList();
      List<Object> revListArgs = Stream.of(
          Stream.of("--pretty=format:%H %ad %(trailers:key=Copyright-Check,valueonly,separator= )",
              "--date=format:%Y", "--no-merges", "HEAD", "--not"),
          baseRefs.stream(),
          Stream.of(root)).<Object>flatMap(s -> s).toList();

      Map<File, Integer> expectedCopyrightYears = Stream.concat(Stream.of(tryGit(
                              spec -> spec.args("rev-list", "--no-commit-header").args(revListArgs),
                              //Earlier git versions don't support --no-commit-header, so we'll try again without
                              spec -> spec.args("rev-list").args(revListArgs)).split("\\R"))
                      .filter(line -> !line.isEmpty() && !line.startsWith("commit")).flatMap(line -> {
                        String[] fields = line.split("\\s+", 3);
                        String commit = fields[0];
                        int year = parseInt(fields[1]);
                        if (fields.length > 2 && fields[2].equalsIgnoreCase("false")) {
                          getLogger().debug("Skipping copyright update checks for: " + git(spec -> spec.args("show", "--oneline", "--no-patch", commit)));
                          return Stream.empty();
                        } else {
                          return Stream.of(git(spec -> spec.args("diff-tree", "-z", "--no-commit-id", "--name-only", "-r",
                                          "--find-renames=100%", "--find-copies=100%",  "--diff-filter=cr", commit)).split("\0"))
                                  .filter(s -> !s.isEmpty()).map(getProject().getRootProject()::file).filter(sourceFiles::contains)
                                  .map(file -> new AbstractMap.SimpleImmutableEntry<>(file, year));
                        }
                      }), matches(PORCELAIN_Z_STATUS_LINE, git(spec -> spec.args("status", "-z", "--porcelain", "--untracked-files=all", root)))
                      .map(line -> line.group(1)).map(getProject().getRootProject()::file).filter(sourceFiles::contains)
                      .map(file -> new AbstractMap.SimpleImmutableEntry<>(file, Instant.now().atZone(ZoneId.systemDefault()).get(ChronoField.YEAR))))
              .collect(Collectors.toMap(Entry::getKey, Entry::getValue, Math::max));

      Set<File> violations = checkFiles(expectedCopyrightYears);

      if (!violations.isEmpty()) {
        throw new IllegalStateException("Copyright statements are incorrect in:\n\t" + violations.stream().map(File::toString).collect(Collectors.joining("\n\t")));
      }
    }

    private Set<File> checkFiles(Map<File, Integer> expectedUpdates) {
      List<Pattern> patterns = getPatterns().get();
      ToIntFunction<Matcher> endYear = getEndYear().get();
      Function<Matcher, String> entity = getEntity().get();
      Predicate<String> expectedEntity = getExpectedEntity().map(Pattern::asMatchPredicate).getOrElse(e -> true);

      return expectedUpdates.entrySet().stream().filter(update -> update.getKey().isFile()).map(update -> {
        try (Stream<String> lines = Files.lines(update.getKey().toPath(), StandardCharsets.UTF_8)) {
          if (lines.flatMap(line -> patterns.stream().map(p -> p.matcher(line))).filter(Matcher::find)
                  .anyMatch(matcher -> expectedEntity.test(entity.apply(matcher)) && update.getValue() <= endYear.applyAsInt(matcher))) {
            return null;
          } else {
            return update.getKey();
          }
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private String git(Action<ExecSpec> action) {
      return getGit().get().execute(action);
    }

    @SafeVarargs
    private final String tryGit(Action<ExecSpec> action, Action<ExecSpec>... fallbacks) {
      return getGit().get().executeOrFallback(action, fallbacks);
    }
  }
}
