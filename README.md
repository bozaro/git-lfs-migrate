# Overview

[![Build Status](https://travis-ci.org/bozaro/git-lfs-migrate.svg?branch=master)](https://travis-ci.org/bozaro/git-lfs-migrate)

Simple project for convert old repository for using git-lfs feature.

# How to use

## Run from binaries

For quick run you need:

 * Install Java 1.8 or later
 * Download binaries archive from: https://github.com/bozaro/git-lfs-migrate/releases/latest
 * After unpacking archive you can run server executing:<br/>
   ```
java -jar build/deploy/git-lfs-migrate.jar -s source-repo.git -d target-repo.git -l http://test:test@lfs-server/
```

## Build from sources

To build from sources you need install JDK 1.8 or later and run build script.

For Linux:

    ./gradlew deployZip

For Windows:

    call gradlew.bat deployZip

When build completes you can convert repository executing:

```
java -jar build/deploy/git-lfs-migrate.jar -s source-repo.git -d target-repo.git -l http://test:test@lfs-server/
```
