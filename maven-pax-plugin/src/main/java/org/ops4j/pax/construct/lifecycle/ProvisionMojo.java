package org.ops4j.pax.construct.lifecycle;

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
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.installer.ArtifactInstallationException;
import org.apache.maven.artifact.installer.ArtifactInstaller;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.artifact.InvalidDependencyVersionException;
import org.ops4j.pax.construct.util.PomUtils;

/**
 * Provision all local and imported bundles onto the selected OSGi framework
 * 
 * @goal provision
 */
public class ProvisionMojo extends AbstractMojo
{
    /**
     * Accumulated set of bundles to be deployed
     */
    private static Set m_bundleArtifacts;

    /**
     * Component factory for Maven artifacts
     * 
     * @component
     */
    private ArtifactFactory m_artifactFactory;

    /**
     * Component for resolving Maven artifacts
     * 
     * @component
     */
    private ArtifactResolver m_resolver;

    /**
     * Component for installing Maven artifacts
     * 
     * @component
     */
    private ArtifactInstaller m_installer;

    /**
     * Component factory for Maven projects
     * 
     * @component
     */
    private MavenProjectBuilder m_projectBuilder;

    /**
     * List of remote Maven repositories for the containing project.
     * 
     * @parameter alias="remoteRepositories" expression="${remoteRepositories}"
     *            default-value="${project.remoteArtifactRepositories}"
     */
    private List m_remoteRepos;

    /**
     * The local Maven repository for the containing project.
     * 
     * @parameter alias="localRepository" expression="${localRepository}"
     * @required
     */
    private ArtifactRepository m_localRepo;

    /**
     * The current Maven project.
     * 
     * @parameter default-value="${project}"
     * @readonly
     */
    private MavenProject m_project;

    /**
     * The current Maven reactor.
     * 
     * @parameter default-value="${reactorProjects}"
     * @readonly
     */
    private List m_reactorProjects;

    /**
     * Name of the OSGi framework to deploy onto.
     * 
     * @parameter alias="framework" expression="${framework}" default-value="felix"
     */
    private String m_framework;

    /**
     * When true, start the OSGi framework and deploy the provisioned bundles.
     * 
     * @parameter alias="deploy" expression="${deploy}" default-value="true"
     */
    private boolean m_deploy;

    /**
     * Comma separated list of additional POMs with bundles as provided dependencies.
     * 
     * @parameter alias="deployPoms" expression="${deployPoms}"
     */
    private String m_deployPoms;

    /**
     * The version of Pax-Runner to use for provisioning.
     * 
     * @parameter alias="runner" expression="${runner}" default-value="0.5.0"
     */
    private String m_runner;

    /**
     * Standard Maven mojo entry-point
     */
    public void execute()
        throws MojoExecutionException
    {
        if( null == m_bundleArtifacts )
        {
            m_bundleArtifacts = new HashSet();

            if( m_deployPoms != null )
            {
                addAdditionalPoms();
            }
        }

        addBundleDependencies( m_project );

        if( m_reactorProjects.indexOf( m_project ) == m_reactorProjects.size() - 1 )
        {
            deployBundles();
        }
    }

    /**
     * Adds project artifact (if it's a bundle) to the deploy list as well as any provided, non-optional dependencies
     * 
     * @param project a Maven project
     */
    void addBundleDependencies( MavenProject project )
    {
        if( PomUtils.isBundleProject( project ) )
        {
            m_bundleArtifacts.add( project.getArtifact() );
        }

        try
        {
            Set artifacts = project.createArtifacts( m_artifactFactory, null, null );
            for( Iterator i = artifacts.iterator(); i.hasNext(); )
            {
                Artifact artifact = (Artifact) i.next();
                if( Artifact.SCOPE_PROVIDED.equals( artifact.getScope() ) && !artifact.isOptional() )
                {
                    m_bundleArtifacts.add( artifact );
                }
            }
        }
        catch( InvalidDependencyVersionException e )
        {
            getLog().warn( "Bad version in dependencies for " + project.getId() );
        }
    }

    /**
     * Add user supplied POMs as if they were in the Maven reactor
     */
    void addAdditionalPoms()
    {
        String[] pomPaths = m_deployPoms.split( "," );
        for( int i = 0; i < pomPaths.length; i++ )
        {
            File pomFile = new File( pomPaths[i] );
            if( pomFile.exists() )
            {
                try
                {
                    addBundleDependencies( m_projectBuilder.build( pomFile, m_localRepo, null ) );
                }
                catch( ProjectBuildingException e )
                {
                    getLog().warn( "Unable to build Maven project for " + pomFile );
                }
            }
        }
    }

    /**
     * Create deployment POM and pass it onto Pax-Runner for provisioning
     * 
     * @throws MojoExecutionException
     */
    void deployBundles()
        throws MojoExecutionException
    {
        if( m_bundleArtifacts.size() == 0 )
        {
            getLog().info( "~~~~~~~~~~~~~~~~~~~" );
            getLog().info( " No bundles found! " );
            getLog().info( "~~~~~~~~~~~~~~~~~~~" );
        }

        List dependencies = new ArrayList();
        for( Iterator i = m_bundleArtifacts.iterator(); i.hasNext(); )
        {
            Artifact artifact = (Artifact) i.next();

            Dependency dep = new Dependency();
            dep.setGroupId( artifact.getGroupId() );
            dep.setArtifactId( artifact.getArtifactId() );
            dep.setVersion( PomUtils.getMetaVersion( artifact ) );

            dependencies.add( dep );
        }

        MavenProject deployProject = createDeploymentProject( dependencies );
        installDeploymentPom( deployProject );

        if( !m_deploy )
        {
            getLog().info( "Deployment complete" );
            return;
        }

        /*
         * Dynamically load the correct Pax-Runner code
         */
        if( m_runner.compareTo( "0.5.0" ) < 0 )
        {
            Class clazz = loadRunnerClass( "org.ops4j.pax", "runner", "org.ops4j.pax.runner.Run", false );
            deployRunnerClassic( clazz, deployProject );
        }
        else
        {
            Class clazz = loadRunnerClass( "org.ops4j.pax.runner", "pax-runner", "org.ops4j.pax.runner.Run", true );
            deployRunnerNG( clazz, deployProject );
        }
    }

    /**
     * Create new POM (based on the root POM) which lists the deployed bundles as dependencies
     * 
     * @param dependencies list of bundles to be deployed
     * @return deployment project
     * @throws MojoExecutionException
     */
    MavenProject createDeploymentProject( List dependencies )
        throws MojoExecutionException
    {
        MavenProject rootProject = (MavenProject) m_reactorProjects.get( 0 );
        MavenProject deployProject = new MavenProject( rootProject );

        String internalId = PomUtils.getCompoundId( rootProject.getGroupId(), rootProject.getArtifactId() );
        deployProject.setGroupId( internalId + ".build" );
        deployProject.setArtifactId( "deployment" );

        // remove unnecessary cruft
        deployProject.setPackaging( "pom" );
        deployProject.getModel().setModules( null );
        deployProject.getModel().setDependencies( dependencies );
        deployProject.getModel().setPluginRepositories( null );
        deployProject.getModel().setReporting( null );
        deployProject.setBuild( null );

        File deployFile = new File( rootProject.getBasedir(), "target/deployment/pom.xml" );

        deployFile.getParentFile().mkdirs();
        deployProject.setFile( deployFile );

        try
        {
            deployProject.writeModel( new FileWriter( deployFile ) );
        }
        catch( IOException e )
        {
            throw new MojoExecutionException( "Unable to write deployment POM " + deployFile );
        }

        return deployProject;
    }

    /**
     * Install deployment POM in the local Maven repository
     * 
     * @param project deployment project
     * @throws MojoExecutionException
     */
    void installDeploymentPom( MavenProject project )
        throws MojoExecutionException
    {
        String groupId = project.getGroupId();
        String artifactId = project.getArtifactId();
        String version = project.getVersion();

        Artifact pomArtifact = m_artifactFactory.createProjectArtifact( groupId, artifactId, version );

        try
        {
            m_installer.install( project.getFile(), pomArtifact, m_localRepo );
        }
        catch( ArtifactInstallationException e )
        {
            throw new MojoExecutionException( "Unable to install deployment POM " + pomArtifact );
        }
    }

    /**
     * Dynamically resolve and load the Pax-Runner class
     * 
     * @param groupId pax-runner group id
     * @param artifactId pax-runner artifact id
     * @param mainClass main pax-runner classname
     * @param needClassifier classify pax-runner artifact according to current JVM
     * @return main pax-runner class
     * @throws MojoExecutionException
     */
    Class loadRunnerClass( String groupId, String artifactId, String mainClass, boolean needClassifier )
        throws MojoExecutionException
    {
        String jdk = null;
        if( needClassifier && System.getProperty( "java.class.version" ).compareTo( "49.0" ) < 0 )
        {
            jdk = "jdk14";
        }

        Artifact jar = m_artifactFactory.createArtifactWithClassifier( groupId, artifactId, m_runner, "jar", jdk );

        try
        {
            m_resolver.resolve( jar, m_remoteRepos, m_localRepo );
        }
        catch( ArtifactNotFoundException e )
        {
            throw new MojoExecutionException( "Unable to find Pax-Runner " + jar );
        }
        catch( ArtifactResolutionException e )
        {
            throw new MojoExecutionException( "Unable to resolve Pax-Runner " + jar );
        }

        URL[] urls = new URL[1];

        try
        {
            urls[0] = jar.getFile().toURI().toURL();
        }
        catch( MalformedURLException e )
        {
            throw new MojoExecutionException( "Bad Jar location " + jar.getFile() );
        }

        try
        {
            ClassLoader loader = new URLClassLoader( urls );
            Thread.currentThread().setContextClassLoader( loader );
            return Class.forName( mainClass, true, loader );
        }
        catch( ClassNotFoundException e )
        {
            throw new MojoExecutionException( "Unable to find entry point " + mainClass + " in " + urls[0] );
        }
    }

    /**
     * Deploy bundles using the 'classic' Pax-Runner
     * 
     * @param mainClass main Pax-Runner class
     * @param project deployment project
     * @throws MojoExecutionException
     */
    void deployRunnerClassic( Class mainClass, MavenProject project )
        throws MojoExecutionException
    {
        String workDir = project.getBasedir() + "/work";

        String cachedPomName = project.getArtifactId() + '_' + project.getVersion() + ".pom";
        File cachedPomFile = new File( workDir + "/lib/" + cachedPomName );

        // Force reload of pom
        cachedPomFile.delete();

        StringBuffer repoListBuilder = new StringBuffer();
        for( Iterator i = m_remoteRepos.iterator(); i.hasNext(); )
        {
            ArtifactRepository repo = (ArtifactRepository) i.next();
            if( repoListBuilder.length() > 0 )
            {
                repoListBuilder.append( ',' );
            }
            repoListBuilder.append( repo.getUrl() );
        }

        String[] deployAppCmds =
        {
            "--dir=" + workDir, "--no-md5", "--platform=" + m_framework, "--profile=default",
            "--repository=" + repoListBuilder.toString(), "--localRepository=" + m_localRepo.getBasedir(),
            project.getGroupId(), project.getArtifactId(), project.getVersion()
        };

        invokePaxRunner( mainClass, deployAppCmds );
    }

    /**
     * Deploy bundles using the new Pax-Runner codebase
     * 
     * @param mainClass main Pax-Runner class
     * @param project deployment project
     * @throws MojoExecutionException
     */
    void deployRunnerNG( Class mainClass, MavenProject project )
        throws MojoExecutionException
    {
        String[] deployAppCmds =
        {
            // TODO: add more options and customization!
            "--overwrite", "--platform=" + m_framework, project.getFile().getAbsolutePath()
        };

        invokePaxRunner( mainClass, deployAppCmds );
    }

    /**
     * Invoke Pax-Runner in-process
     * 
     * @param mainClass main Pax-Runner class
     * @param commands array of command-line options
     * @throws MojoExecutionException
     */
    void invokePaxRunner( Class mainClass, String[] commands )
        throws MojoExecutionException
    {
        Class[] paramTypes = new Class[1];
        paramTypes[0] = String[].class;

        Object[] paramValues = new Object[1];
        paramValues[0] = commands;

        try
        {
            Method entryPoint = mainClass.getMethod( "main", paramTypes );
            entryPoint.invoke( null, paramValues );
        }
        catch( NoSuchMethodException e )
        {
            throw new MojoExecutionException( "Unable to find Pax-Runner entry point" );
        }
        catch( IllegalAccessException e )
        {
            throw new MojoExecutionException( "Unable to access Pax-Runner entry point" );
        }
        catch( InvocationTargetException e )
        {
            throw new MojoExecutionException( "Pax-Runner exception", e );
        }
    }
}