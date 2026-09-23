/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.archicontribs.modelrepository.GitHelper;
import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.archimatetool.editor.utils.FileUtils;

/**
 * Tests for {@link ArchiRepository#updateManifestFromPullDiff}: verifies the
 * manifest is updated incrementally from a commit-to-commit diff, using bytes
 * read from the working tree (not the Git blob), instead of a full model
 * folder rehash.
 */
@SuppressWarnings("nls")
public class ArchiRepositoryPullManifestTests {

    @AfterEach
    public void runOnceAfterEachTest() throws IOException {
        FileUtils.deleteFolder(GitHelper.getTempTestsFolder());
    }

    private File newRepoFolder() {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "testRepo");
        repoFolder.mkdirs();
        return repoFolder;
    }

    private void writeModelFile(File repoFolder, String relPath, String content) throws IOException {
        File file = new File(new File(repoFolder, IGraficoConstants.MODEL_FOLDER), relPath);
        file.getParentFile().mkdirs();
        Files.writeString(file.toPath(), content);
    }

    private ObjectId commitAll(Repository repository, String message) throws Exception {
        try(Git git = Git.wrap(repository)) {
            AddCommand addCommand = git.add();
            addCommand.addFilepattern(".");
            addCommand.setUpdate(false);
            addCommand.call();

            CommitCommand commitCommand = git.commit();
            commitCommand.setAuthor("Test", "Test");
            commitCommand.setMessage(message);
            return commitCommand.call().getId();
        }
    }

    @Test
    public void updateManifestFromPullDiff_AddsNewFileHash() throws Exception {
        File repoFolder = newRepoFolder();
        Repository repository = GitHelper.createNewRepository(repoFolder);

        writeModelFile(repoFolder, "Folder_1.xml", "<xml>v1</xml>");
        ObjectId before = commitAll(repository, "Initial commit");

        writeModelFile(repoFolder, "Folder_2.xml", "<xml>new</xml>");
        ObjectId after = commitAll(repository, "Add second file");

        File modelFolder = new File(repoFolder, IGraficoConstants.MODEL_FOLDER);
        GraficoManifest manifest = new GraficoManifest(modelFolder);

        ArchiRepository.updateManifestFromPullDiff(repository, before, after, manifest);

        assertTrue(manifest.containsKey("Folder_2.xml"));
        Path diskPath = modelFolder.toPath().resolve("Folder_2.xml");
        assertEquals(GraficoManifest.md5(Files.readAllBytes(diskPath)), manifest.get("Folder_2.xml"));

        repository.close();
    }

    @Test
    public void updateManifestFromPullDiff_HashesChangedFileFromDisk() throws Exception {
        File repoFolder = newRepoFolder();
        Repository repository = GitHelper.createNewRepository(repoFolder);

        writeModelFile(repoFolder, "Folder_1.xml", "<xml>v1</xml>");
        ObjectId before = commitAll(repository, "Initial commit");

        writeModelFile(repoFolder, "Folder_1.xml", "<xml>v2</xml>");
        ObjectId after = commitAll(repository, "Modify file");

        File modelFolder = new File(repoFolder, IGraficoConstants.MODEL_FOLDER);
        GraficoManifest manifest = new GraficoManifest(modelFolder);
        manifest.put("Folder_1.xml", "<xml>v1</xml>".getBytes());

        ArchiRepository.updateManifestFromPullDiff(repository, before, after, manifest);

        Path diskPath = modelFolder.toPath().resolve("Folder_1.xml");
        assertEquals(GraficoManifest.md5(Files.readAllBytes(diskPath)), manifest.get("Folder_1.xml"));
        assertFalse(manifest.get("Folder_1.xml").equals(GraficoManifest.md5("<xml>v1</xml>".getBytes())));

        repository.close();
    }

    @Test
    public void updateManifestFromPullDiff_RemovesDeletedFile() throws Exception {
        File repoFolder = newRepoFolder();
        Repository repository = GitHelper.createNewRepository(repoFolder);

        writeModelFile(repoFolder, "Folder_1.xml", "<xml>v1</xml>");
        writeModelFile(repoFolder, "Folder_2.xml", "<xml>v2</xml>");
        ObjectId before = commitAll(repository, "Initial commit");

        File deleted = new File(new File(repoFolder, IGraficoConstants.MODEL_FOLDER), "Folder_2.xml");
        Files.delete(deleted.toPath());
        ObjectId after = commitAll(repository, "Remove second file");

        File modelFolder = new File(repoFolder, IGraficoConstants.MODEL_FOLDER);
        GraficoManifest manifest = new GraficoManifest(modelFolder);
        manifest.put("Folder_1.xml", "<xml>v1</xml>".getBytes());
        manifest.put("Folder_2.xml", "<xml>v2</xml>".getBytes());

        ArchiRepository.updateManifestFromPullDiff(repository, before, after, manifest);

        assertTrue(manifest.containsKey("Folder_1.xml"));
        assertFalse(manifest.containsKey("Folder_2.xml"));
        assertNull(manifest.get("Folder_2.xml"));

        repository.close();
    }

    @Test
    public void updateManifestFromPullDiff_IgnoresNonXmlAndNonModelFiles() throws Exception {
        File repoFolder = newRepoFolder();
        Repository repository = GitHelper.createNewRepository(repoFolder);

        writeModelFile(repoFolder, "Folder_1.xml", "<xml>v1</xml>");
        ObjectId before = commitAll(repository, "Initial commit");

        writeModelFile(repoFolder, "notes.txt", "not tracked by manifest");
        Files.writeString(new File(repoFolder, "README.md").toPath(), "outside model folder");
        ObjectId after = commitAll(repository, "Add non-manifest files");

        File modelFolder = new File(repoFolder, IGraficoConstants.MODEL_FOLDER);
        GraficoManifest manifest = new GraficoManifest(modelFolder);
        manifest.put("Folder_1.xml", "<xml>v1</xml>".getBytes());

        ArchiRepository.updateManifestFromPullDiff(repository, before, after, manifest);

        assertEquals(1, manifest.size());
        assertTrue(manifest.containsKey("Folder_1.xml"));

        repository.close();
    }

    @Test
    public void updateManifestFromPullDiff_UsesWorkingTreeBytesNotGitBlobBytes() throws Exception {
        // Regression test for Finding 4: the manifest must reflect on-disk bytes
        // (post-checkout, post-filter) rather than the raw Git blob bytes.
        File repoFolder = newRepoFolder();
        Repository repository = GitHelper.createNewRepository(repoFolder);

        writeModelFile(repoFolder, "Folder_1.xml", "<xml>v1</xml>");
        ObjectId before = commitAll(repository, "Initial commit");

        // Commit content, then simulate a checkout-time transformation by rewriting
        // the working tree copy with different bytes before diffing.
        writeModelFile(repoFolder, "Folder_1.xml", "<xml>v2 as committed</xml>");
        ObjectId after = commitAll(repository, "Modify file");

        File modelFolder = new File(repoFolder, IGraficoConstants.MODEL_FOLDER);
        Path diskPath = modelFolder.toPath().resolve("Folder_1.xml");
        Files.writeString(diskPath, "<xml>v2 as checked out (post-filter)</xml>");

        GraficoManifest manifest = new GraficoManifest(modelFolder);
        manifest.put("Folder_1.xml", "<xml>v1</xml>".getBytes());

        ArchiRepository.updateManifestFromPullDiff(repository, before, after, manifest);

        // The manifest hash must match what is actually on disk, not the committed blob.
        assertEquals(GraficoManifest.md5(Files.readAllBytes(diskPath)), manifest.get("Folder_1.xml"));

        repository.close();
    }
}
