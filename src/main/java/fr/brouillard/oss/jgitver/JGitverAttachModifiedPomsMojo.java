// @formatter:off
/**
 * Copyright (C) 2016 Matthieu Brouillard [http://oss.brouillard.fr/jgitver-maven-plugin] (matthieu@brouillard.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// @formatter:on
package fr.brouillard.oss.jgitver;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.xml.bind.JAXBException;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.InstantiationStrategy;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.logging.console.ConsoleLogger;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

/**
 * Works in conjunction with JGitverModelProcessor.
 */
@Mojo(name = JGitverAttachModifiedPomsMojo.GOAL_ATTACH_MODIFIED_POMS,
        instantiationStrategy = InstantiationStrategy.SINGLETON, threadSafe = true)
public class JGitverAttachModifiedPomsMojo extends AbstractMojo {
    public static final String GOAL_ATTACH_MODIFIED_POMS = "attach-modified-poms";

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession mavenSession;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        String className = JGitverModelProcessorWorkingConfiguration.class.getName();

        if (Objects.isNull(mavenSession.getUserProperties().get(className))) {
            getLog().warn(GOAL_ATTACH_MODIFIED_POMS + "shouldn't be executed alone. The Mojo "
                    + "is a part of the plugin and executed automatically.");
            return;
        }

        String content = mavenSession.getUserProperties().getProperty((className));
        if ("-".equalsIgnoreCase(content)) {
            // We don't need to attach modified poms anymore.
            return;
        }

        try {
            JGitverModelProcessorWorkingConfiguration jGitverModelProcessorWorkingConfiguration =
                    JGitverModelProcessorWorkingConfiguration.serializeFrom(content);

            attachModifiedPomFilesToTheProject(mavenSession.getAllProjects(),
                    jGitverModelProcessorWorkingConfiguration, mavenSession, new
                            ConsoleLogger());

            mavenSession.getUserProperties().setProperty(className, "-");
        } catch (XmlPullParserException | IOException | JAXBException ex) {
            throw new MojoExecutionException("Unable to execute goal: "
                    + JGitverAttachModifiedPomsMojo.GOAL_ATTACH_MODIFIED_POMS, ex);
        }
    }

    /**
     * Attach modified POM files to the projects so install/deployed files contains new version.
     *
     * @param projects           projects.
     * @param workingConfiguration the jgitver working configuration serialized during initialization.
     * @param mavenSession the current maven build session
     * @param logger the logger to report to
     * @throws IOException if project model cannot be read correctly
     * @throws XmlPullParserException if project model cannot be interpreted correctly
     */
    public void attachModifiedPomFilesToTheProject(List<MavenProject> projects, JGitverModelProcessorWorkingConfiguration workingConfiguration, MavenSession mavenSession, Logger logger) throws IOException, XmlPullParserException {
        for (MavenProject project : projects) {
            Map<GAV, String> newProjectVersions = workingConfiguration.getNewProjectVersions();
            Model model = JGitverUtils.loadInitialModel(project.getFile());
            GAV initalProjectGAV = GAV.from(model);     // SUPPRESS CHECKSTYLE AbbreviationAsWordInName

            logger.debug("about to change file pom for: " + initalProjectGAV);

            if (newProjectVersions.containsKey(initalProjectGAV)) {
                model.setVersion(newProjectVersions.get(initalProjectGAV));
            }

            if (model.getParent() != null) {
                GAV parentGAV = GAV.from(model.getParent());    // SUPPRESS CHECKSTYLE AbbreviationAsWordInName

                if (newProjectVersions.keySet().contains(parentGAV)) {
                    // parent has been modified
                    model.getParent().setVersion(newProjectVersions.get(parentGAV));
                }
            }
            
            updateSCM(model, workingConfiguration);

            File newPom = JGitverUtils.createPomDumpFile();
            JGitverUtils.writeModelPom(model, newPom);
            logger.debug("    new pom file created for " + initalProjectGAV + " under " + newPom);

            JGitverUtils.setProjectPomFile(project, newPom, logger);
            logger.debug("    pom file set");
        }
    }


    /**
     * Modifies the SCM node if one exists
     * @param model the model to modify
     * @param workingConfiguration the configuration where to find the SHA1
     */
    private void updateSCM(Model model, JGitverModelProcessorWorkingConfiguration workingConfiguration) {
        if (model.getScm() != null && workingConfiguration.getSha1() != null) {
            // we can update the SCM tag
            if (workingConfiguration.getBranch() != null) {
                // we are on a branch, let'see if we are not building a tag version
                if (workingConfiguration.getScmTag() == null) {
                    // we're really on a branch and not building a tagged version
                    model.getScm().setTag(workingConfiguration.getBranch());
                } else {
                    // we're on a branch but current commit has a tagged version
                    model.getScm().setTag(workingConfiguration.getScmTag());
                }
            } else {
                // we are on a detached head
                if (workingConfiguration.getScmTag() == null) {
                    // no tag directly for current head
                    model.getScm().setTag(workingConfiguration.getSha1());
                } else {
                    // let's use tag of the commit
                    model.getScm().setTag(workingConfiguration.getScmTag());
                }
            }
        }
    }
}
