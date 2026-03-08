/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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

    private final File fModelFolder;
    private final Map<String, String> entries = new HashMap<>();

    /**
     * Create an empty manifest for the given model folder
     * 
     * @param modelFolder model folder this manifest belongs to
     */
    public GraficoManifest(File modelFolder) {
        fModelFolder = modelFolder;
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
        GraficoManifest manifest = new GraficoManifest(modelFolder);
        File manifestFile = new File(modelFolder, MANIFEST_NAME);
        if(manifestFile.exists()) {
            for(String line : Files.readAllLines(manifestFile.toPath())) {
                String[] kv = line.split("=", 2); //$NON-NLS-1$
                if(kv.length == 2) {
                    manifest.entries.put(kv[0], kv[1]);
                }
            }
        }
        System.err.println("MANIFEST Loaded: " + manifest.size() + " entries from " + manifestFile.getAbsolutePath());
        return manifest;
    }

    /**
     * Save the manifest to its model folder.
     * Entries are written in sorted order for stable diffs.
     * 
     * @throws IOException
     */
    public void save() throws IOException {
        File manifestFile = new File(fModelFolder, MANIFEST_NAME);
        Map<String, String> sorted = new TreeMap<>(entries);
        List<String> lines = sorted.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()) //$NON-NLS-1$
                .toList();
        Files.write(manifestFile.toPath(), lines);
        System.err.println("MANIFEST Saved: " + sorted.size() + " entries to " + manifestFile.getAbsolutePath());
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
        GraficoManifest manifest = new GraficoManifest(modelFolder);
        Path base = modelFolder.toPath();
        Files.walk(base)
             .filter(p -> p.toString().endsWith(".xml")) //$NON-NLS-1$
             .forEach(p -> {
                 try {
                     String relPath = base.relativize(p).toString().replace(File.separatorChar, '/');
                     manifest.entries.put(relPath, md5(Files.readAllBytes(p)));
                 }
                 catch(IOException ex) {
                     throw new RuntimeException(ex);
                 }
             });
        return manifest;
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
        return entries.get(path);
    }

    /**
     * Remove the entry for the given path.
     * 
     * @param path relative path key
     */
    public void remove(String path) {
        entries.remove(path);
    }

    /**
     * Returns true if the manifest contains an entry for the given path.
     * 
     * @param path relative path key
     */
    public boolean containsKey(String path) {
        return entries.containsKey(path);
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
}