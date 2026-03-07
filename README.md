# Archi Model Repository Plug-in - Manifest Fork

## What is this?
This is a fork of [coArchi](https://github.com/archimatetool/archi-modelrepository-plugin/) to reduce IO overhead incurred by large models (i.e. ~100K elements).

In very large models, here are some areas that may cause performance issues due to IO bottlenecks:
1. On every action (commit, refresh, publish), the model repository is deleted and exported. This fork generates a manifest of md5 hashes for every element it is about to export. The element is only exported if the hash is different (or does not exist). Elements that are in the manifest, but no longer in the current export are deleted on disk.
2. After exporting, the equivalent of "git add ." is called. This fork can use the manifest to selective and explicitly stage changes via "git add" or "git remove" on each item. 
3. After a git fetch/pull, the XML on disk needs to be loaded back into the model. Instead of blindly reloading every element, this fork aims to selectively reload/unload only the XML files that have changed.

## Base Features / Progress
- Basic manifest: completed
- Manifest-guided git operations: completed
- Selective EMF reload: in progress

## Smaller performance enhancements or QoL features
- Use NIO and batch read XMLs during reload: completed
- Update the manifest with hashes of newly pulled files (instead of waiting for the next export): TODO
- Add Repository (git clone) supports shallow clones and selective commits: completed
- Prompt for Primary Password before doing any operations (e.g. fail faster): completed

## Usage Notes
This fork is a drop-in replacement.
No new logging is introduced, but there are debug messages sent to stderr.

e.g. view using: `Archi.exe > archi.log 2>&1`

## About Archi
Archi® is a free, open source, cross-platform tool and editor to create ArchiMate models.

The Archi® modelling tool is targeted toward all levels of Enterprise Architects and Modellers. It provides a low cost to entry solution to users who may be making their first steps in the ArchiMate modelling language, or who are looking for a free, cross-platform ArchiMate modelling tool for their company or institution and wish to engage with the language within a TOGAF® or other Enterprise Architecture framework.

ArchiMate® is an open and independent Enterprise Architecture modelling language that supports the description, analysis and visualization of architecture within and across business domains. ArchiMate is one of the open standards hosted by The Open Group and is fully aligned with TOGAF®.

The Archi website is here: [https://www.archimatetool.com](https://www.archimatetool.com)
