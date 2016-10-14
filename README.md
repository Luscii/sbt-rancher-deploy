# `sbt-rancher-deploy` #

`sbt-rancher-deploy` is a plugin for sbt that allows deployment of
Docker images to Rancher environments.

You can use a plugin like [`sbt-docker`][1] to build Docker images of your projects and push the to a Docker image repository of your liking, and then use `sbt-rancher-deploy` to deploy these images to a Rancher stack.

[1]:https://github.com/marcuslonnberg/sbt-docker

## Using `sbt-rancher-deploy` in your project ##

To enable the plugin, create a file `project/rancherDeployment.sbt` with the following content:

```scala
lazy val root = (project in file(".")).dependsOn(rancherDeployPlugin)
lazy val rancherDeployPlugin = RootProject(uri("ssh://git@github.com/focuscura/sbt-rancher-deploy.git#0.1.2"))
```

Then, in your main `build.sbt` file, you can use the following settings in your projects (assuming you use `sbt-docker` to build the Docker images:

```scala
lazy val `my-project` = project.in(file("my-project"))
  .settings(
    dockerFile in docker := {
      // task definition for building the Docker image
      // ...
    },
    imageNames in docker := Seq(ImageName(s"user/my-image")),
    rancherServices := Seq("my-service-1", "my-service-2" /*, ... */), // The rancher services to deploy this project to
    rancherDockerImage := Def.task {
      (imageNames in docker).value.head.toString()
    }.dependsOn(sbtdocker.DockerKeys.dockerPush).dependsOn(sbtdocker.DockerKeys.docker).value,
    /*
    further settings
    ...
    */
  )
```


## Settings ##

### `rancherDeployDryRun` ###

A task returning a `Boolean` that indicates whether a deployment must be simulated.

When this task returns `true`, a dry-run deployment is performed, ie. no actual API calls are done at the selected rancher environment.  
The default value returned by this task is `false`.

### `rancherDockerImage` ###

A task returning a `String` containing the name of the Docker image that should be deployed at the Rancher service(s) returned by the `rancherServices` task. It is advisable to make this task depend on the task(s) that build and push the Docker image for this project (as shown in the above code sample), so that when a deployment is performed, these tasks are run before the actual deployment.

### `rancherServices` ###

A task returning a `Seq[String]` containing the names of the Rancher service(s) this project's Docker image is deployed to. When this task returns an empty `Seq`, no deployment will be performed for this project.  
The default return value for this task is `Seq()`

### `rancherShouldFinishUpgrade` ###

A task returning a `Boolean` indicating whether an upgrade of the services in `rancherServices` should be finished (when `true`) or should be rolled back (when `false`). This task is a logical location to run your integration tests against the Rancher environment that is being updated.
The default return value for this task is `true`


## Deployment configuration ##

In your project's base directory, create a file with the following contents, changing the values applicable to your Rancher environments:

```hocon
environments {
  # an object with environment names as its keys, each
  # mapping to an object like the one below
  test {
    # Which git branches are accepted to be deployed to this environment?
    # "*" counts as a wildcard
    allowed-branches = ["*"]
    # Allow uncommitted changes to be deployed to this environment?

    allow-uncommitted-changes = true

    #rancher settings for this environment
    rancher {
      # The URL where rancher server can be reached, no path after the hostname
      url = "[test env Rancher URL]"
      # The rancher stack where the deployed services can be found
      stack = "my-stack-test"
      basic-auth {
        username = "[my Rancher api access key]"
        password = "[my Rancher api secret key]"
      }
    }
  }
  accept {
    allowed-branches = ["accept"]
    allow-uncommitted-changes = false
    rancher {
      url = "[accept env Rancher URL]"
      stack = "my-stack-accept"
      basic-auth {
        username = "[my Rancher api access key]"
        password = "[my Rancher api secret key]"
      }
    }
  }
  production {
    allowed-branches = ["production"]
    allow-uncommitted-changes = false
    rancher {
      url = "[production env Rancher URL]"
      stack = "my-stack-production"
      basic-auth {
        username = "[my Rancher api access key]"
        password = "[my Rancher api secret key]"
      }
    }
  }
}
```

The keys of the environments (`test`, `accept` and `production` in the above configuration) are the same as the ones used when a deployment is performed, as described below. You can use other names for the keys, eg. `staging`, `live`, etc. 

## Deploying to Rancher ##

You can use the `rancher-deploy-to` sbt command to perform a deployment to the environment you give as this command's first argument. So to deploy to the test environment, use `rancher-deploy-to test`.

Running this command will first check if the git branch of the working copy from which the command is run is one of the branches in `environment.[name].allowed-branches`, and if `environment.[name].allow-uncommitted-changes` corresponds with the state of your working copy. If neither of these checks succeed, deployment is aborted.  
Next, the project's unit tests (when present) are run, and if any test fails, the deployment is aborted.
A Rancher service upgrade is performed for all services, and depending on the return value of `rancherShouldFinishUpgrade`, finishes the service upgrade, or rolls back the upgrade.

### Aggregate projects ###

The `rancher-deploy-to` command performs the tasks below on the currently selected project and its aggregates in the following order:

1. Run unit tests for the current project and all its aggregates, abort if any test fails.
2. Upgrade all services for the current project and its aggregates.
3. Collect the results of the `rancherShouldFinishUpgrade` of the current project and its aggregates. If any of these results is `false`, roll back all the upgraded services and abort. If all of these results are `true`, finish the upgrades of all services, and return success.




## Development of `sbt-rancher-deploy` ##

### Testing the `sbt-rancher-deploy` plugin ###

In the `src/sbt-test` folder there are several projects that are used as test cases for the `sbt-scripted` plugin. A description of how this works can be found [here][1].

Basically it boils down to this:

Each folder in `src/sbt-test/sbt-rancher-deploy` is a seperate sbt test project that makes use of the `sbt-rancher-deploy` plugin. Each of these test projects contain a test script, conveniently named `test`. This file contains sbt commands that are executed on a test run when the `scripted` sbt task is started from the `sbt-rancher-deploy` root. The test project directory is copied to a temporary location, `scripted` then starts a seperate sbt process in which the commands from the test script are run on the test project copy.

The format of the `test` file is this:

* `#` starts a one-line comment
* `> <sbt-task-or-command> [arg ...]` sends a task to sbt (and tests if it succeeds)
* `$ <file-command> [arg ...]` performs a file command (and tests if it succeeds)
* `-> <sbt-task-or-command> [arg ...]` sends a task to sbt, but expects it to fail
* `-$ <file-command> [arg ...]` performs a file command, but expects it to fail

A file command is one of the following

* `touch path [path ...]` creates or updates the timestamp on the files
* `delete path [path ...]` deletes the files
* `exists path [path ...]` checks if the files exist
* `mkdir path [path ...]` creates dirs
* `absent path [path ...]` checks if the files don't exist
* `newer source target` checks if source is newer
* `pause` pauses until enter is pressed
* `sleep time` sleeps
* `exec command [args ...]` runs the command in another process
* `copy-file fromPath toPath` copies the file
* `copy fromPath [fromPath ...] toDir` copies the paths to toDir preserving relative structure
* `copy-flat fromPath [fromPath ...] toDir` copies the paths to toDir flat


[1]: http://eed3si9n.com/testing-sbt-plugins
