package org.ops4j.pax.construct;

/*
 * Copyright 2007 Stuart McCulloch
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.io.FileReader;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.model.fileset.FileSet;
import org.apache.maven.shared.model.fileset.util.FileSetManager;
import org.kxml2.kdom.Document;
import org.kxml2.kdom.Element;

/**
 * Removes a bundle from the project.
 * 
 * @goal remove-bundle
 */
public final class RemoveBundleMojo extends AbstractMojo
{
    /**
     * The containing OSGi project
     * 
     * @parameter expression="${project}"
     */
    protected MavenProject project;

    /**
     * The name of the bundle to remove.
     * 
     * @parameter expression="${name}"
     * @required
     */
    private String bundleName;

    // cached model of removed bundle
    private static Model bundleModel;

    public void execute()
        throws MojoExecutionException
    {
        try
        {
            if( null == bundleModel )
            {
                if( project.getParent() != null )
                {
                    throw new MojoExecutionException( "This command must be run from the project root" );
                }

                File bundlePomFile = PomUtils.findBundlePom( project.getBasedir(), bundleName );

                if( bundlePomFile == null || !bundlePomFile.exists() )
                {
                    throw new MojoExecutionException( "Cannot find bundle " + bundleName );
                }

                FileReader input = new FileReader( bundlePomFile );
                MavenXpp3Reader modelReader = new MavenXpp3Reader();
                bundleModel = modelReader.read( input );

                // safety check: don't remove module poms!
                if( bundleModel.getModules().size() > 0 )
                {
                    throw new MojoExecutionException( "Folder " + bundleName + " is not a bundle" );
                }

                FileSet bundleFiles = new FileSet();

                File bundlePomFolder = bundlePomFile.getParentFile();
                bundleFiles.setDirectory( bundlePomFolder.getParent() );
                bundleFiles.addInclude( bundlePomFolder.getName() );

                new FileSetManager( getLog(), true ).delete( bundleFiles );
            }

            // ignore remove bundle project
            if( !project.getFile().exists() )
            {
                return;
            }

            Document pom = PomUtils.readPom( project.getFile() );
            Element projectElem = pom.getElement( null, "project" );

            Dependency dependency = PomUtils.getBundleDependency( bundleModel );

            PomUtils.removeDependency( projectElem, dependency );
            PomUtils.removeModule( projectElem, new File( bundleName ).getName() );
            PomUtils.writePom( project.getFile(), pom );
        }
        catch( Exception e )
        {
            throw new MojoExecutionException( "Unable to remove the requested bundle", e );
        }
    }

}
