# Digital Archaeologist

A lightweight, read-only Java console app for exploring a folder's file history and contents.

## Run

Requires Java 17 or newer. From this folder:

    javac DigitalArchaeologist.java
    java DigitalArchaeologist

Choose an option to view a folder overview, locate exact duplicate files with SHA-256, or export a JSON inventory. The scanner skips common .git, node_modules, and __pycache__ folders. It reads file metadata and contents for hashing; it does not modify the scanned files.
