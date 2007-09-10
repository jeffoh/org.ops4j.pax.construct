package org.ops4j.pax.construct.archetype;

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

import org.apache.maven.archetype.FileUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.archetype.MavenArchetypeMojo;
import org.apache.maven.project.MavenProject;
import org.ops4j.pax.construct.util.DirUtils;
import org.ops4j.pax.construct.util.PomUtils;
import org.ops4j.pax.construct.util.PomUtils.Pom;
import org.ops4j.pax.construct.util.ReflectUtils.ReflectMojo;

/**
 * @aggregator true
 */
public abstract class AbstractPaxArchetypeMojo extends MavenArchetypeMojo
{
    static final String PAX_ARCHETYPE_GROUP_ID = "org.ops4j.pax.construct";

    /**
     * @parameter expression="${archetypeVersion}" default-value="${plugin.version}"
     */
    String archetypeVersion;

    /**
     * @parameter expression="${targetDirectory}" default-value="${project.basedir}"
     */
    File targetDirectory;

    /**
     * @parameter expression="${compactNames}" default-value="true"
     */
    boolean compactNames;

    /**
     * @parameter expression="${attachPom}" default-value="true"
     */
    boolean attachPom;

    /**
     * @parameter expression="${overwrite}"
     */
    boolean overwrite;

    MavenProject project;
    ReflectMojo m_mojo;
    File m_pomFile;

    public final void execute()
        throws MojoExecutionException
    {
        updateFields();

        prepareTarget();
        super.execute();
        postProcess();
    }

    final void updateFields()
    {
        m_mojo = new ReflectMojo( this, MavenArchetypeMojo.class );

        m_mojo.setField( "archetypeGroupId", PAX_ARCHETYPE_GROUP_ID );
        m_mojo.setField( "archetypeVersion", archetypeVersion );
        m_mojo.setField( "project", project );

        targetDirectory = FileUtils.resolveFile( targetDirectory, "" );
        m_mojo.setField( "basedir", targetDirectory.getPath() );

        // these must be set by the various archetype sub-classes
        // setField( "archetypeArtifactId", archetypeArtifactId );
        // setField( "groupId", groupId );
        // setField( "artifactId", artifactId );
        // setField( "version", version );
        // setField( "packageName", packageName );

        updateExtensionFields();
    }

    abstract void updateExtensionFields();

    void prepareTarget()
        throws MojoExecutionException
    {
        String artifactId = (String) m_mojo.getField( "artifactId" );
        File pomDirectory = new File( targetDirectory, artifactId );

        m_pomFile = new File( pomDirectory, "pom.xml" );
        if( overwrite && m_pomFile.exists() )
        {
            m_pomFile.delete();
        }

        if( attachPom )
        {
            Pom modulesPom = DirUtils.createModuleTree( project.getBasedir(), targetDirectory );
            if( null != modulesPom )
            {
                pomDirectory.mkdirs();
                modulesPom.addModule( artifactId, overwrite );
                modulesPom.write();
            }
        }
    }

    void postProcess()
        throws MojoExecutionException
    {
        Pom parentPom = DirUtils.findPom( targetDirectory, getParentId() );
        if( null != parentPom )
        {
            Pom thisPom = PomUtils.readPom( m_pomFile );

            String relativePath = null;
            String[] pivot = DirUtils.calculateRelativePath( thisPom.getBasedir(), parentPom.getBasedir() );
            if( null != pivot )
            {
                relativePath = pivot[0] + pivot[2];
            }

            thisPom.setParent( parentPom, relativePath, overwrite );
            thisPom.write();
        }
    }

    abstract String getParentId();

    final String calculateGroupMarker( String groupId, String artifactId )
    {
        if( compactNames )
        {
            if( artifactId.startsWith( groupId + '.' ) || artifactId.equals( groupId ) )
            {
                return '=' + groupId;
            }
            else if( groupId.endsWith( '.' + artifactId ) )
            {
                return '~' + artifactId;
            }
        }

        return '+' + groupId;
    }

    final String getCompactName( String groupId, String artifactId )
    {
        if( compactNames )
        {
            return PomUtils.getCompoundName( groupId, artifactId );
        }

        return groupId + '.' + artifactId;
    }
}
