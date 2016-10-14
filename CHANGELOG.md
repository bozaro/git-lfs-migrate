# Changes

## 0.2.3

 * Allow load globs from file (#19, thanks to @leth).
 * Don't convert zero length file to a pointer file (#21, thanks to @kna).

## 0.2.2

 * Fix object size being 0 when retrying request (#14, #16, thanks to @robinst).
 * Set User-Agent header for HTTP requests (#15, thanks to @robinst).

## 0.2.1

 * Add --no-check-certificate flag #10.

## 0.2.0

 * Support an arbitrary glob, not just a suffix (#8, #9).

## 0.1.1

 * Add --check-lfs flag for troubleshooting.
 * Add check LFS server on startup.
 * Add more verbose HTTP error messages.
 * Fix bash code formatting in README.md (thanks to @brad).

## 0.1.0

 * Use git-lfs batch API for uploading files.
 * Update git-lfs-java to 0.6.0.

## 0.0.14

 * Update git-lfs-java to 0.5.0 (fix #4: multithread uploading).

## 0.0.13

 * Update git-lfs-java to 0.4.0.

## 0.0.12

 * Add git url support.
 * Fix some errors github-uploading errors.

## 0.0.11

 * Add HTTP redirect support.

## 0.0.10

 * Don't save locallty files when --lfs flag used.
 * Add cache for already uploaded files hash.

## 0.0.9

 * Don't convert pointers for already uploaded LFS files.

## 0.0.8

 * Fix ignoring executable files bug.

## 0.0.7

 * Trying to fulfill each HTTP request 3 times before the error.

## 0.0.6

 * Fix gradle wrapper.
 * Add ending slash to URL if not exists.

## 0.0.5

 * Performance improvement (upload files to LFS server in multiple threads).

## 0.0.3

 * Add tag support.

## 0.0.2

 * Fix pointer format (end empty newline from end of pointer file).

## 0.0.1

 * First release.
