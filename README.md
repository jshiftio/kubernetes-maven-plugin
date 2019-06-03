## kubernetes-maven-plugin

[![Circle CI](https://circleci.com/gh/jshiftio/kubernetes-maven-plugin/tree/master.svg?style=shield)](https://circleci.com/gh/jshiftio/kubernetes-maven-plugin/tree/master)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=jshiftio_kubernetes-maven-plugin&metric=sqale_rating)](https://sonarcloud.io/dashboard?id=jshiftio_kubernetes-maven-plugin)
[![Technical Debt](https://sonarcloud.io/api/project_badges/measure?project=jshiftio_kubernetes-maven-plugin&metric=sqale_index)](https://sonarcloud.io/dashboard?id=jshiftio_kubernetes-maven-plugin)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=jshiftio_kubernetes-maven-plugin&metric=coverage)](https://sonarcloud.io/dashboard?id=jshiftio_kubernetes-maven-plugin)

### Introduction
This Maven plugin is a one-stop-shop for building and deploying Java applications for Docker, Kubernetes and OpenShift. It brings your Java applications on to Kubernetes and OpenShift. It provides a tight integration into maven and benefits from the build configuration already provided. It focuses on three tasks:
+ Building Docker images
+ Creating OpenShift and Kubernetes resources
+ Deploy application on Kubernetes and OpenShift

### Usage
To enable kubernetes maven plugin on your project just add this to the plugins sections of your pom.xml:

```
      <plugin>
        <groupId>io.jshift</groupId>
        <artifactId>kubernetes-maven-plugin</artifactId>
        <version>${jshift.kubernetes.version}</version>
      </plugin>
```

| Goal                                          | Description                           |
| --------------------------------------------- | ------------------------------------- |
| [`kubernetes:resource`](https://fabric8io.github.io/fabric8-maven-plugin/#fabric8:resource) | Create Kubernetes and OpenShift resource descriptors |
| [`kubernetes:build`](https://fabric8io.github.io/fabric8-maven-plugin/#fabric8:build) | Build Docker images |
| [`kubernetes:push`](https://fabric8io.github.io/fabric8-maven-plugin/#fabric8:push) | Push Docker images to a registry  |
| [`kubernetes:deploy`](https://fabric8io.github.io/fabric8-maven-plugin/#fabric8:deploy) | Deploy Kubernetes / OpenShift resource objects to a cluster  |
| [`kubernetes:watch`](https://fabric8io.github.io/fabric8-maven-plugin/#fabric8:watch) | Watch for doing rebuilds and restarts |

### Features

* Dealing with Docker images and hence inherits its flexible and powerful configuration.
* Supports both Kubernetes and OpenShift descriptors
* OpenShift Docker builds with a binary source (as an alternative to a direct image build agains a Docker daemon)
* Various configuration styles:
  * **Zero Configuration** for a quick ramp-up where opinionated defaults will be pre-selected.
  * **Inline Configuration** within the plugin configuration in an XML syntax.
  * **External Configuration** templates of the real deployment descriptors which are enriched by the plugin.
* Flexible customization:
  * **Generators** analyze the Maven build and generated automatic Docker image configurations for certain systems (spring-boot, plain java, karaf ...)
  * **Enrichers** extend the Kubernetes / OpenShift resource descriptors by extra information like SCM labels and can add default objects like Services.
  * Generators and Enrichers can be individually configured and combined into *profiles*

### OpenShift and Kubernetes Compatibility

:heavy_check_mark: : Supported, all available features can be used

:x: : Not supported at all

:large_blue_circle: : Supported, but not all features can be used

##### Kubernetes

|     KMP      | Kubernetes 1.12.0 | Kubernetes 1.11.0 | Kubernetes 1.10.0 | Kubernetes 1.9.0 | Kubernetes 1.8.0 | Kubernetes 1.7.0 | Kubernetes 1.6.0 | Kubernetes 1.5.1 | Kubernetes 1.4.0 |
|--------------|-------------------|-------------------|-------------------|------------------|------------------|------------------|------------------|------------------|------------------|
| KMP 4.0.0    |        :heavy_check_mark:          |        :heavy_check_mark:          |       :heavy_check_mark:           |       :heavy_check_mark:          |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :x:         |        :x:         |
| KMP 4.0.0-M2 |        :large_blue_circle:          |        :large_blue_circle:          |       :large_blue_circle:           |       :heavy_check_mark:          |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :x:         |        :x:         |
| KMP 4.0.0-M1 |        :large_blue_circle:          |        :large_blue_circle:          |       :large_blue_circle:           |       :heavy_check_mark:          |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :x:         |        :x:         |
| KMP 3.5.42   |        :large_blue_circle:          |        :large_blue_circle:          |       :large_blue_circle:           |       :large_blue_circle:          |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |
| KMP 3.5.41   |        :x:          |        :x:          |       :x:           |       :large_blue_circle:          |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |
| KMP 3.5.40   |        :x:          |        :x:          |       :x:           |       :large_blue_circle:          |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |
| KMP 3.5.39   |        :x:          |        :x:          |       :x:           |       :x:          |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |
| KMP 3.5.38   |        :x:          |        :x:          |       :x:           |       :x:          |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |
| KMP 3.5.37   |        :x:          |        :x:          |       :x:           |       :x:          |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |
| KMP 3.5.35   |        :x:          |        :x:          |       :x:           |       :x:          |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |
| KMP 3.5.34   |        :x:          |        :x:          |       :x:           |       :x:          |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |
| KMP 3.5.33   |        :x:          |        :x:          |       :x:           |       :x:          |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |
| KMP 3.5.32   |        :x:          |        :x:          |       :x:           |       :x:          |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |        :heavy_check_mark:         |
