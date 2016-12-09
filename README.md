# Overview

[![Build Status](https://travis-ci.org/bozaro/git-lfs-migrate.svg?branch=master)](https://travis-ci.org/bozaro/git-lfs-migrate)

Simple project for convert old repository for using git-lfs feature.

# How to use

## Run from binaries

For quick run you need:

 * Install Java 1.8 or later
 * Download binaries archive from: https://github.com/bozaro/git-lfs-migrate/releases/latest
 * After unpacking archive you can run server executing:

   ```bash
   java -jar build/deploy/git-lfs-migrate.jar -s source-repo.git -d target-repo.git -l http://test:test@lfs-server/ "*.psd" "*.zip" "*.bin"
   ```

For example, you can convert bozaro/git-lfs-migrate to bozaro/git-lfs-migrate-converted by commands:

```bash
#!/bin/bash
# Clone original repository
git clone --mirror git@github.com:bozaro/git-lfs-migrate.git

# Convert repository with moving .md and .jar file to LFS
#
# Usage: <main class> [options] LFS file glob patterns
#   Options:
#     -c, --cache
#        Source repository
#        Default: .
#         --check-lfs
#      Check LFS server settings and exit
#      Default: false
#   * -d, --destination
#        Destination repository
#     -g, --git
#       GIT repository url (ignored with --lfs parameter)
#     -h, --help
#        Show help
#        Default: false
#     -l, --lfs
#        LFS server url (can be determinated by --git paramter)
#   * -s, --source
#        Source repository
#     -u, --upload-threads
#        HTTP upload thread count
#        Default: 4
#     -t, --write-threads
#        IO thread count
#        Default: 2
#     --glob-file
#        File containing glob patterns
java -jar git-lfs-migrate.jar \
     -s git-lfs-migrate.git \
     -d git-lfs-migrate-converted.git \
     -g git@github.com:bozaro/git-lfs-migrate-converted.git \
     "*.md" \
     "*.jar"

# Push coverted repository to new repository
cd git-lfs-migrate-converted.git
git fsck && git push --mirror git@github.com:bozaro/git-lfs-migrate-converted.git
```

After that you with have:

 * New repository bozaro/git-lfs-migrate-converted
 * All *.md and *.jar in this repository will stored in LFS storage
 * All revisions on this repository will have modified or created .gitattributes file with new lines like:<br/>
```
*.md    filter=lfs diff=lfs merge=lfs -text
*.jar   filter=lfs diff=lfs merge=lfs -text
```

Supported Git url formats:

 * https://user:passw0rd@github.com/foo/bar.git
 * http://user:passw0rd@github.com/foo/bar.git
 * git://user:passw0rd@github.com/foo/bar.git
 * ssh://git@github.com/foo/bar.git
 * git@github.com:foo/bar.git

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
java -jar build/deploy/git-lfs-migrate.jar -s source-repo.git -d target-repo.git -l http://test:test@lfs-server/ "*.psd" "*.zip" "*.bin"
```
