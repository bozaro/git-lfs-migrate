# Overview

[![Build Status](https://travis-ci.org/bozaro/git-lfs-migrate.svg?branch=master)](https://travis-ci.org/bozaro/git-lfs-migrate)

Simple project for convert old repository for using git-lfs feature.

# How to use

## How to get LFS server URL

You can get correct LFS server URL by command like:

```
ssh git@github.com git-lfs-authenticate bozaro/git-lfs-migrate upload
```

For GitHub LFS server URL looks like:

```
https://bozaro:*****@api.github.com/lfs/bozaro/git-lfs-migrate
```

## Run from binaries

For quick run you need:

 * Install Java 1.8 or later
 * Download binaries archive from: https://github.com/bozaro/git-lfs-migrate/releases/latest
 * After unpacking archive you can run server executing:<br/>
   ```
java -jar build/deploy/git-lfs-migrate.jar -s source-repo.git -d target-repo.git -l http://test:test@lfs-server/ .psd .zip .bin
```

## Build from sources

To build from sources you need install JDK 1.8 or later and run build script.

For Linux:

    ./gradlew deployZip

For Windows:

    call gradlew.bat deployZip

When build completes you can convert repository executing:

```
java -jar build/deploy/git-lfs-migrate.jar -s source-repo.git -d target-repo.git -l http://test:test@lfs-server/ .psd .zip .bin
```
