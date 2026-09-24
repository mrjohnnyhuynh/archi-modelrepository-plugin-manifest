/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.archicontribs.modelrepository.GitHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.archimatetool.editor.utils.FileUtils;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;

@SuppressWarnings("nls")
public class GraficoModelExporterTests {

    @AfterEach
    public void runOnceAfterEachTest() throws IOException {
        FileUtils.deleteFolder(GitHelper.getTempTestsFolder());
    }

    private GraficoModelExporter newExporter(File repoFolder) {
        IArchimateModel model = IArchimateFactory.eINSTANCE.createArchimateModel();
        return new GraficoModelExporter(model, repoFolder);
    }

    @Test
    public void validateImagePath_AcceptsSimpleRelativePath() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "testRepo");
        repoFolder.mkdirs();
        GraficoModelExporter exporter = newExporter(repoFolder);

        // Archive entry keys always include the images/ prefix (see
        // com.archimatetool.editor.model.impl.ArchiveManager) and must resolve under
        // the images folder without duplicating that prefix.
        Path resolved = exporter.validateImagePath("images/picture.png");
        Path expected = repoFolder.toPath().resolve(IGraficoConstants.IMAGES_FOLDER).resolve("picture.png").normalize();
        assertEquals(expected, resolved);
    }

    @Test
    public void validateImagePath_AcceptsNestedRelativePath() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "testRepo");
        repoFolder.mkdirs();
        GraficoModelExporter exporter = newExporter(repoFolder);

        Path resolved = exporter.validateImagePath("images/sub/picture.png");
        Path expected = repoFolder.toPath().resolve(IGraficoConstants.IMAGES_FOLDER).resolve("sub/picture.png").normalize();
        assertEquals(expected, resolved);
    }

    @Test
    public void validateImagePath_RejectsAbsolutePath() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "testRepo");
        repoFolder.mkdirs();
        GraficoModelExporter exporter = newExporter(repoFolder);

        String absolute = new File(GitHelper.getTempTestsFolder(), "elsewhere.png").getAbsolutePath();
        assertThrows(IOException.class, () -> exporter.validateImagePath(absolute));
    }

    @Test
    public void validateImagePath_RejectsBackslashes() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "testRepo");
        repoFolder.mkdirs();
        GraficoModelExporter exporter = newExporter(repoFolder);

        assertThrows(IOException.class, () -> exporter.validateImagePath("images\\picture.png"));
    }

    @Test
    public void validateImagePath_RejectsDotDotTraversal() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "testRepo");
        repoFolder.mkdirs();
        GraficoModelExporter exporter = newExporter(repoFolder);

        assertThrows(IOException.class, () -> exporter.validateImagePath("../../etc/passwd.png"));
        assertThrows(IOException.class, () -> exporter.validateImagePath("images/../../outside.png"));
    }

    @Test
    public void validateImagePath_RejectsPathOutsideImagesFolder() throws Exception {
        File repoFolder = new File(GitHelper.getTempTestsFolder(), "testRepo");
        repoFolder.mkdirs();
        GraficoModelExporter exporter = newExporter(repoFolder);

        // A repo-root-relative path that does not start with images/ must be rejected,
        // rather than silently escaping the images folder via resolve().normalize().
        assertThrows(IOException.class, () -> exporter.validateImagePath("model/picture.png"));
    }

    @Test
    public void writeAtomically_ReplacesFileAndLeavesNoTemporaryFile() throws Exception {
        Path folder = GitHelper.getTempTestsFolder().toPath().resolve("testRepo");
        Files.createDirectories(folder);
        Path file = folder.resolve("resource.xml");
        byte[] expected = "<element/>".getBytes(StandardCharsets.UTF_8);
        Files.write(file, "<element".getBytes(StandardCharsets.UTF_8));

        GraficoModelExporter.writeAtomically(file, expected);

        assertEquals("<element/>", Files.readString(file, StandardCharsets.UTF_8));
        try(java.util.stream.Stream<Path> stream = Files.list(folder)) {
            assertEquals(0, stream.filter(path -> path.toString().endsWith(".tmp")).count());
        }
    }
}
