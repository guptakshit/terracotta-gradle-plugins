/*
 * Copyright Terracotta, Inc.
 * Copyright IBM Corp. 2024, 2025
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

package org.terracotta.build.plugins.buildinfo;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.provider.Provider;
import org.gradle.process.internal.ExecException;
import org.jetbrains.annotations.NotNull;
import org.terracotta.build.services.Git;

import java.time.ZonedDateTime;
import java.util.Objects;

@SuppressWarnings("UnstableApiUsage")
public class BuildInfoPlugin implements Plugin<Project> {

  @Override
  public void apply(@NotNull Project project) {
    final Provider<String> overrideVersion = project.getProviders().gradleProperty("overrideVersion");
    final Provider<String> defaultVersion = project.getProviders().gradleProperty("defaultVersion");
    final Provider<String> version = project.provider(project::getVersion).filter(v -> v != Project.DEFAULT_VERSION).map(Objects::toString);
    final Provider<Git> git = Git.getOrInstall(project);
    final BuildInfoExtension extension = project.getExtensions().create(BuildInfoExtension.class, "buildInfo", BuildInfoExtension.class);

    extension.getBuildTimestamp().convention(ZonedDateTime.now())
        .finalizeValueOnRead();

    extension.getVersion().convention(overrideVersion
        .orElse(defaultVersion)
        .orElse(version)
        .map(StructuredVersion::parse)
    ).finalizeValueOnRead();

    extension.getHasLocalChange().convention(git.map(g -> {
      try {
        return g.hasLocalChange();
      } catch (ExecException e) {
        return false;
      }
    })).finalizeValueOnRead();

    extension.getBranch().convention(git.map(g -> {
      try {
        return g.getBranch();
      } catch (ExecException e) {
        return "<unknown>";
      }
    })).finalizeValueOnRead();

    extension.getCommitHash().convention(git.map(g -> {
      try {
        return g.getCommitHash();
      } catch (ExecException e) {
        return "<unknown>";
      }
    })).finalizeValueOnRead();

    extension.getRevision().convention(git.flatMap(g ->
        extension.getHasLocalChange().flatMap(hasLocalChange ->
            hasLocalChange ?
                extension.getCommitHash().map(commitHash -> {
                  // note: this try-catch is in reality not needed since git.hash and git.diff won't be called if
                  // the git command is not available (in this case, hasLocalChange is false)
                  try {
                    return commitHash + "+" + System.getProperty("user.name") + ":" + g.hash(g.diff(commitHash));
                  } catch (ExecException e) {
                    return "<unknown>";
                  }
                }) :
                extension.getCommitHash()))
    ).finalizeValueOnRead();

    if (extension.getVersion().isPresent()) {
      project.setVersion(extension.getVersion().get());
    }
  }
}
