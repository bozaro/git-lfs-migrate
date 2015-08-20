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
   ```bash
java -jar build/deploy/git-lfs-migrate.jar -s source-repo.git -d target-repo.git -l http://test:test@lfs-server/ .psd .zip .bin
```

For example, you can convert bozaro/git-lfs-migrate to bozaro/git-lfs-migrate-converted by commands:

```bash
#!/bin/bash
# Clone original repository
git clone --mirror git@github.com:bozaro/git-lfs-migrate.git

# Convert repository with moving .md and .jar file to LFS
#
# Usage: <main class> [options] LFS file suffixes
#  Options:
#  * -d, --destination
#       Destination repository
#    -h, --help
#       Show help
#       Default: false
#    -l, --lfs
#       LFS URL
#  * -s, --source
#       Source repository
#    -t, --threads
#       Thread count
#       Default: 8
java -jar git-lfs-migrate.jar \
     -s git-lfs-migrate.git \
     -d git-lfs-migrate-converted.git \
     -l https://bozaro:*****@api.github.com/lfs/bozaro/git-lfs-migrate-converted \
     .md \
     .jar

# Push coverted repository to new repository
cd git-lfs-migrate-converted.git
git fsck && git push --mirror git@github.com:bozaro/git-lfs-migrate-converted.git
```

After that you with have:

 * New repository bozaro/git-lfs-migrate-converted
 * All *.md and *.jar in this repository will stored in LFS storage
 * All revisions on this repository will have modified or created .gitattributes file with new lines like:<br/>
```
*.md    filter=lfs diff=lfs merge=lfs -crlf
*.jar   filter=lfs diff=lfs merge=lfs -crlf
```

## Build from sources

To build from sources you need install JDK 1.8 or later and run build script.

For Linux:

```bash
./gradlew deployZip
```

For Windows:

```bash
call gradlew.bat deployZip
```

When build completes you can convert repository executing:

```bash
java -jar build/deploy/git-lfs-migrate.jar -s source-repo.git -d target-repo.git -l http://test:test@lfs-server/ .psd .zip .bin
```
