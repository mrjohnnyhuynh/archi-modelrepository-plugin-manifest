/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.archicontribs.modelrepository.GitHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.archimatetool.editor.utils.FileUtils;

@SuppressWarnings("nls")
public class GraficoManifestTests {

    @AfterEach
    public void runOnceAfterEachTest() throws IOException {
        FileUtils.deleteFolder(GitHelper.getTempTestsFolder());
    }

    private File newModelFolder() {
        File modelFolder = new File(GitHelper.getTempTestsFolder(), "model");
        modelFolder.mkdirs();
        return modelFolder;
    }

    // --- validateKey ---

    @Test
    public void validateKey_AcceptsSimpleRelativeXmlPath() {
        File modelFolder = newModelFolder();
        assertEquals("Folder_1.xml", GraficoManifest.validateKey("Folder_1.xml", modelFolder.toPath()));
        assertEquals("sub/Folder_1.xml", GraficoManifest.validateKey("sub/Folder_1.xml", modelFolder.toPath()));
    }

    @Test
    public void validateKey_RejectsNullOrBlank() {
        File modelFolder = newModelFolder();
        assertThrows(IllegalArgumentException.class, () -> GraficoManifest.validateKey(null, modelFolder.toPath()));
        assertThrows(IllegalArgumentException.class, () -> GraficoManifest.validateKey("", modelFolder.toPath()));
        assertThrows(IllegalArgumentException.class, () -> GraficoManifest.validateKey("   ", modelFolder.toPath()));
    }

    @Test
    public void validateKey_RejectsNonXmlEntries() {
        File modelFolder = newModelFolder();
        assertThrows(IllegalArgumentException.class, () -> GraficoManifest.validateKey("evil.txt", modelFolder.toPath()));
        assertThrows(IllegalArgumentException.class, () -> GraficoManifest.validateKey("evil.sh", modelFolder.toPath()));
    }

    @Test
    public void validateKey_RejectsBackslashes() {
        File modelFolder = newModelFolder();
        assertThrows(IllegalArgumentException.class, () -> GraficoManifest.validateKey("sub\\Folder_1.xml", modelFolder.toPath()));
    }

    @Test
    public void validateKey_RejectsAbsolutePaths() {
        File modelFolder = newModelFolder();
        String absolute = new File(modelFolder, "outside.xml").getAbsolutePath();
        assertThrows(IllegalArgumentException.class, () -> GraficoManifest.validateKey(absolute, modelFolder.toPath()));
    }

    @Test
    public void validateKey_RejectsDotDotTraversal() {
        File modelFolder = newModelFolder();
        assertThrows(IllegalArgumentException.class, () -> GraficoManifest.validateKey("../outside.xml", modelFolder.toPath()));
        assertThrows(IllegalArgumentException.class, () -> GraficoManifest.validateKey("sub/../../outside.xml", modelFolder.toPath()));
        assertThrows(IllegalArgumentException.class, () -> GraficoManifest.validateKey("./Folder_1.xml", modelFolder.toPath()));
    }

    // --- save / load round trip and atomicity ---

    @Test
    public void save_ThenLoad_RoundTripsEntries() throws Exception {
        File modelFolder = newModelFolder();
        Path manifestFile = modelFolder.toPath().resolve(".git-manifest-test");

        GraficoManifest manifest = new GraficoManifest(modelFolder.toPath(), manifestFile);
        manifest.put("Folder_1.xml", "hello".getBytes());
        manifest.put("sub/Folder_2.xml", "world".getBytes());
        manifest.save();

        assertTrue(Files.exists(manifestFile));

        GraficoManifest loaded = GraficoManifest.load(modelFolder, manifestFile);
        assertEquals(2, loaded.size());
        assertEquals(manifest.get("Folder_1.xml"), loaded.get("Folder_1.xml"));
        assertEquals(manifest.get("sub/Folder_2.xml"), loaded.get("sub/Folder_2.xml"));
    }

    @Test
    public void save_LeavesNoTemporaryFilesBehind() throws Exception {
        File modelFolder = newModelFolder();
        Path manifestFile = modelFolder.toPath().resolve(".git-manifest-test");

        GraficoManifest manifest = new GraficoManifest(modelFolder.toPath(), manifestFile);
        manifest.put("Folder_1.xml", "hello".getBytes());
        manifest.save();

        long tempFileCount;
        try(java.util.stream.Stream<Path> stream = Files.list(manifestFile.getParent())) {
            tempFileCount = stream.filter(p -> p.toString().endsWith(".tmp")).count();
        }
        assertEquals(0, tempFileCount);
    }

    @Test
    public void load_ReturnsEmptyManifestWhenFileMissing() throws Exception {
        File modelFolder = newModelFolder();
        Path manifestFile = modelFolder.toPath().resolve(".git-manifest-test");

        GraficoManifest manifest = GraficoManifest.load(modelFolder, manifestFile);
        assertEquals(0, manifest.size());
    }

    @Test
    public void load_FallsBackToLegacyModelFolderManifest() throws Exception {
        File modelFolder = newModelFolder();
        Path legacy = modelFolder.toPath().resolve(GraficoManifest.MANIFEST_NAME);
        Files.writeString(legacy, GraficoManifest.FORMAT_HEADER + "\nFolder_1.xml=ABCDEF");

        Path newLocation = modelFolder.toPath().getParent().resolve(".git-manifest-new");
        GraficoManifest manifest = GraficoManifest.load(modelFolder, newLocation);

        assertEquals("ABCDEF", manifest.get("Folder_1.xml"));
    }

    @Test
    public void load_IgnoresPreviousFormatToForceSafeRegeneration() throws Exception {
        File modelFolder = newModelFolder();
        Path manifestFile = modelFolder.toPath().resolve(".git-manifest-test");
        Files.writeString(manifestFile, "Folder_1.xml=ABCDEF");

        GraficoManifest manifest = GraficoManifest.load(modelFolder, manifestFile);

        assertEquals(0, manifest.size());
    }

    @Test
    public void load_RejectsMalformedEntryLine() throws Exception {
        File modelFolder = newModelFolder();
        Path manifestFile = modelFolder.toPath().resolve(".git-manifest-test");
        Files.writeString(manifestFile, GraficoManifest.FORMAT_HEADER + "\nnot-a-valid-line-without-equals");

        assertThrows(IOException.class, () -> GraficoManifest.load(modelFolder, manifestFile));
    }

    @Test
    public void load_RejectsTraversalKeyInManifestFile() throws Exception {
        File modelFolder = newModelFolder();
        Path manifestFile = modelFolder.toPath().resolve(".git-manifest-test");
        Files.writeString(manifestFile, GraficoManifest.FORMAT_HEADER + "\n../outside.xml=ABCDEF");

        assertThrows(IOException.class, () -> GraficoManifest.load(modelFolder, manifestFile));
    }

    // --- buildFromDisk ---

    @Test
    public void buildFromDisk_HashesOnlyXmlFiles() throws Exception {
        File modelFolder = newModelFolder();
        Files.writeString(modelFolder.toPath().resolve("Folder_1.xml"), "<xml/>");
        Files.writeString(modelFolder.toPath().resolve("notes.txt"), "ignore me");

        GraficoManifest manifest = GraficoManifest.buildFromDisk(modelFolder);
        assertEquals(1, manifest.size());
        assertTrue(manifest.containsKey("Folder_1.xml"));
    }

    @Test
    public void buildFromDisk_RecursesIntoSubfolders() throws Exception {
        File modelFolder = newModelFolder();
        File subFolder = new File(modelFolder, "sub");
        subFolder.mkdirs();
        Files.writeString(subFolder.toPath().resolve("Folder_2.xml"), "<xml/>");

        GraficoManifest manifest = GraficoManifest.buildFromDisk(modelFolder);
        assertTrue(manifest.containsKey("sub/Folder_2.xml"));
    }

    // --- get/put/remove/containsKey validate their argument ---

    @Test
    public void get_ReturnsNullForMissingEntry() {
        File modelFolder = newModelFolder();
        GraficoManifest manifest = new GraficoManifest(modelFolder);
        assertNull(manifest.get("Folder_1.xml"));
    }

    @Test
    public void get_RejectsTraversalArgument() {
        File modelFolder = newModelFolder();
        GraficoManifest manifest = new GraficoManifest(modelFolder);
        assertThrows(IllegalArgumentException.class, () -> manifest.get("../outside.xml"));
    }

    @Test
    public void put_RejectsTraversalArgument() {
        File modelFolder = newModelFolder();
        GraficoManifest manifest = new GraficoManifest(modelFolder);
        assertThrows(IllegalArgumentException.class, () -> manifest.put("../outside.xml", "x".getBytes()));
    }

    @Test
    public void remove_RemovesExistingEntry() {
        File modelFolder = newModelFolder();
        GraficoManifest manifest = new GraficoManifest(modelFolder);
        manifest.put("Folder_1.xml", "hello".getBytes());
        assertTrue(manifest.containsKey("Folder_1.xml"));

        manifest.remove("Folder_1.xml");
        assertFalse(manifest.containsKey("Folder_1.xml"));
    }

    // --- resolveKey ---

    @Test
    public void resolveKey_ResolvesUnderModelRoot() throws Exception {
        File modelFolder = newModelFolder();
        GraficoManifest manifest = new GraficoManifest(modelFolder);

        Path resolved = manifest.resolveKey("sub/Folder_1.xml");
        assertEquals(modelFolder.toPath().resolve("sub/Folder_1.xml").normalize(), resolved);
    }

    @Test
    public void resolveKey_RejectsSymlinkEscapeFromModelRoot() throws Exception {
        File modelFolder = newModelFolder();
        File outside = new File(GitHelper.getTempTestsFolder(), "outside");
        outside.mkdirs();

        File link = new File(modelFolder, "escape");
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath());
        }
        catch(UnsupportedOperationException | IOException ex) {
            // Symlinks may require elevated privileges on Windows; skip if unsupported here.
            return;
        }

        Files.writeString(outside.toPath().resolve("Folder_1.xml"), "<xml/>");

        GraficoManifest manifest = new GraficoManifest(modelFolder);
        assertThrows(IOException.class, () -> manifest.resolveKey("escape/Folder_1.xml"));
    }

    @Test
    public void md5_IsDeterministicAndUppercaseHex() {
        String hash1 = GraficoManifest.md5("hello".getBytes());
        String hash2 = GraficoManifest.md5("hello".getBytes());
        assertEquals(hash1, hash2);
        assertEquals(hash1, hash1.toUpperCase());
    }
}
