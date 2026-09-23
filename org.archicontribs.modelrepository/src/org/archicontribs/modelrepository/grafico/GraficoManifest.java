/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Manages the Grafico manifest file (.grafico_manifest).
 * 
 * The manifest records a hash for each XML file in the model folder,
 * allowing the exporter to skip writing files whose content has not changed,
 * and to delete files that are no longer part of the model.
 * 
 * It is also used after a clone or pull to record the on-disk state without
 * requiring a full export.
 */
public class GraficoManifest {

    public static final String MANIFEST_NAME = ".grafico_manifest";

    private final Path fModelRoot;
    private final Path fManifestFile;
    private final Map<String, String> entries = new HashMap<>();

    /**
     * Create an empty manifest for the given model folder
     * 
     * @param modelFolder model folder this manifest belongs to
     */
    public GraficoManifest(File modelFolder) {
        this(modelFolder.toPath(), modelFolder.toPath().resolve(MANIFEST_NAME));
    }

    /**
     * Create a manifest for a model root and an explicit storage path.
     *
     * @param modelFolder model root used to validate entry paths
     * @param manifestFile manifest storage path
     */
    public GraficoManifest(Path modelFolder, Path manifestFile) {
        fModelRoot = modelFolder.toAbsolutePath().normalize();
        fManifestFile = manifestFile.toAbsolutePath().normalize();
    }

    /**
     * Load a manifest from the given model folder.
     * Returns an empty manifest if no manifest file exists.
     * 
     * @param modelFolder model folder containing the manifest
     * @return GraficoManifest loaded from disk
     * @throws IOException
     */
    public static GraficoManifest load(File modelFolder) throws IOException {
        return load(modelFolder, modelFolder.toPath().resolve(MANIFEST_NAME));
    }

    /**
     * Load a manifest from an explicit storage path.
     */
    public static GraficoManifest load(File modelFolder, Path manifestFile) throws IOException {
        GraficoManifest manifest = new GraficoManifest(modelFolder.toPath(), manifestFile);
        Path source = manifestFile;
        Path legacy = modelFolder.toPath().resolve(MANIFEST_NAME);
        if(!Files.exists(source) && !source.equals(legacy) && Files.exists(legacy)) {
            source = legacy;
        }
        if(Files.exists(source)) {
            for(String line : Files.readAllLines(source)) {
                String[] kv = line.split("=", 2); //$NON-NLS-1$
                if(kv.length == 2) {
                    try {
                        String key = validateKey(kv[0], modelFolder.toPath());
                        manifest.resolveKey(key);
                        manifest.entries.put(key, kv[1]);
                    }
                    catch(IllegalArgumentException ex) {
                        throw new IOException("Invalid Grafico manifest entry: " + kv[0], ex); //$NON-NLS-1$
                    }
                }
                else if(!line.isBlank()) {
                    throw new IOException("Malformed Grafico manifest entry."); //$NON-NLS-1$
                }
            }
        }
        return manifest;
    }

    /**
     * Save the manifest to its model folder.
     * Entries are written in sorted order for stable diffs.
     * 
     * @throws IOException
     */
    public void save() throws IOException {
        Map<String, String> sorted = new TreeMap<>(entries);
        List<String> lines = sorted.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()) //$NON-NLS-1$
                .toList();
        Path target = fManifestFile;
        Path parent = target.getParent();
        if(parent == null) {
            throw new IOException("Manifest path has no parent directory."); //$NON-NLS-1$
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, MANIFEST_NAME, ".tmp"); //$NON-NLS-1$
        try {
            Files.write(temporary, lines);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch(java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Build a manifest by hashing all XML files currently on disk in the model folder.
     * 
     * Used after a hard reset, where the correct files are already on disk
     * but no in-memory model or commit diff is available.
     * 
     * @param modelFolder model folder to scan
     * @return GraficoManifest built from disk
     * @throws IOException
     */
    public static GraficoManifest buildFromDisk(File modelFolder) throws IOException {
        return buildFromDisk(modelFolder, modelFolder.toPath().resolve(MANIFEST_NAME));
    }

    public static GraficoManifest buildFromDisk(File modelFolder, Path manifestFile) throws IOException {
        GraficoManifest manifest = new GraficoManifest(modelFolder.toPath(), manifestFile);
        Path base = modelFolder.toPath();
        try(Stream<Path> paths = Files.walk(base)) {
            paths.filter(p -> p.toString().endsWith(".xml")) //$NON-NLS-1$
                 .filter(p -> !Files.isSymbolicLink(p))
                 .forEach(p -> {
                     try {
                         String relPath = validateKey(base.relativize(p).toString().replace(File.separatorChar, '/'), base);
                         manifest.entries.put(relPath, md5(Files.readAllBytes(p)));
                     }
                     catch(IOException ex) {
                         throw new ManifestBuildException(ex);
                     }
                 });
        }
        catch(ManifestBuildException ex) {
            throw ex.getCause();
        }
        return manifest;
    }

    private static class ManifestBuildException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ManifestBuildException(IOException cause) {
            super(cause);
        }

        @Override
        public IOException getCause() {
            return (IOException)super.getCause();
        }
    }

    /**
     * Add or update an entry, hashing the given bytes.
     * Returns the computed hash so callers can compare without a second lookup.
     * 
     * @param path relative path key
     * @param data file bytes to hash
     * @return the computed MD5 hash
     */
    public String put(String path, byte[] data) {
        path = validateKey(path, fModelRoot);
        String hash = md5(data);
        entries.put(path, hash);
        return hash;
    }

    /**
     * Get the hash for the given path, or null if not present.
     * 
     * @param path relative path key
     * @return MD5 hash string, or null
     */
    public String get(String path) {
        return entries.get(validateKey(path, fModelRoot));
    }

    /**
     * Remove the entry for the given path.
     * 
     * @param path relative path key
     */
    public void remove(String path) {
        entries.remove(validateKey(path, fModelRoot));
    }

    /**
     * Returns true if the manifest contains an entry for the given path.
     * 
     * @param path relative path key
     */
    public boolean containsKey(String path) {
        return entries.containsKey(validateKey(path, fModelRoot));
    }

    /**
     * Returns the set of all path keys in this manifest.
     */
    public Set<String> keySet() {
        return entries.keySet();
    }

    /**
     * Returns the set of all path keys in this manifest.
     */
    public int size() {
        return entries.size();
    }
    
    /**
     * Compute the MD5 hash of a byte array, returned as an uppercase hex string.
     * 
     * @param data bytes to hash
     * @return uppercase hex MD5 string
     */
    public static String md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5"); //$NON-NLS-1$
            return HexFormat.of().formatHex(md.digest(data)).toUpperCase();
        }
        catch(NoSuchAlgorithmException ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Validate and canonicalize a manifest entry path.
     */
    public static String validateKey(String path, Path modelRoot) {
            if(path == null || path.isBlank() || path.indexOf('\\') >= 0 || !path.endsWith(".xml")) { //$NON-NLS-1$
                throw new IllegalArgumentException("Invalid Grafico manifest path: " + path); //$NON-NLS-1$
            }

            final Path relative;
            try {
                relative = Paths.get(path);
            }
            catch(InvalidPathException ex) {
                throw new IllegalArgumentException("Invalid Grafico manifest path: " + path, ex); //$NON-NLS-1$
            }
            if(relative.isAbsolute()) {
                throw new IllegalArgumentException("Absolute Grafico manifest paths are not allowed."); //$NON-NLS-1$
            }
            for(Path segment : relative) {
                if(segment.toString().equals(".") || segment.toString().equals("..")) { //$NON-NLS-1$ //$NON-NLS-2$
                    throw new IllegalArgumentException("Traversal in Grafico manifest path is not allowed."); //$NON-NLS-1$
                }
            }

            Path root = modelRoot.toAbsolutePath().normalize();
            Path resolved = root.resolve(relative).normalize();
            if(!resolved.startsWith(root)) {
                throw new IllegalArgumentException("Grafico manifest path escapes the model root."); //$NON-NLS-1$
            }
            return relative.toString().replace(File.separatorChar, '/');
    }

    /**
     * Resolve a validated key and reject symlink escapes from the model root.
     */
    public Path resolveKey(String path) throws IOException {
        String key = validateKey(path, fModelRoot);
        Path resolved = fModelRoot.resolve(Paths.get(key)).normalize();
        if(Files.exists(resolved)) {
            Path realRoot = fModelRoot.toRealPath();
            if(!resolved.toRealPath().startsWith(realRoot)) {
                throw new IOException("Grafico manifest path escapes the model root: " + path); //$NON-NLS-1$
            }
        }
        return resolved;
    }
}