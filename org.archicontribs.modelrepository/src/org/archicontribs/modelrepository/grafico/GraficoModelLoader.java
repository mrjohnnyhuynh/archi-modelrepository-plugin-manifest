/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.archicontribs.modelrepository.grafico.GraficoModelImporter.ModelChange;
import org.archicontribs.modelrepository.grafico.GraficoModelImporter.UnresolvedObject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.swt.custom.BusyIndicator;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;

import com.archimatetool.editor.diagram.DiagramEditorInput;
import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.editor.model.IEditorModelManager;
import com.archimatetool.editor.ui.services.EditorManager;
import com.archimatetool.editor.utils.StringUtils;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.INameable;
import com.archimatetool.model.util.ArchimateModelUtils;

/**
 * Import a model from Grafico files and handle conflicts, re-opening diagrams and status
 * 
 * @author Phillip Beauvoir
 */
public class GraficoModelLoader {
    
    private IArchiRepository fRepository;
    
    private List<IIdentifier> fRestoredObjects;
    
    public GraficoModelLoader(IArchiRepository repository) {
        fRepository = repository;
    }
    
    /**
     * Load the model.
     *
     * If the model is already open and the Git reflog can supply a
     * previous HEAD commit, an incremental reload is attempted: only the XML
     * files that changed between the two commits are processed.  On any error
     * the code falls through to the original full-reload path.
     *
     * @return the loaded (or updated) model
     * @throws IOException
     */
    public IArchimateModel loadModel() throws IOException {
        fRestoredObjects = null;
        
        long timeStart = System.currentTimeMillis();

        IArchimateModel model = fRepository.locateModel();
        if(model != null) {
            try {
                IArchimateModel incrementalResult = tryIncrementalLoad(model, timeStart);
                if(incrementalResult != null) {
                    return incrementalResult;
                }
            }
            catch(Exception ex) {
                ModelRepositoryPlugin.getInstance().getLog().error("Incremental model load failed – falling back to full reload", ex);
            }
        }

        model = fullLoad();
        
        long timeEnd = System.currentTimeMillis();
        String msg = String.format("*** Model Loaded from XML in " + (timeEnd-timeStart) + "ms");
        System.err.println(msg);
        ModelRepositoryPlugin.getInstance().getLog().info(msg);

		return model;
    }

    /**
     * Attempt an incremental update of existingModel via GIT DIFF current HEAD against reflog previous HEAD
     *
     * @return the updated model on success, or null otherwise (e.g. no reflog, no changes, or any error during the update process)
     * @throws Exception propagated to the caller which will fall back to full load
     */
    private IArchimateModel tryIncrementalLoad(IArchimateModel existingModel, long timeStart) throws Exception {
        try(Git git = Git.open(fRepository.getLocalRepositoryFolder())) {
            Repository repo = git.getRepository();

            ObjectId currentHead  = repo.resolve(Constants.HEAD);
            // HEAD@{1} is the reflog entry immediately before the most recent change
            // (i.e. the pre-pull commit).  Returns null on a fresh clone / no reflog.
            ObjectId previousHead = repo.resolve("HEAD@{1}");

            if(previousHead == null || previousHead.equals(currentHead)) {
                return null;
            }

            List<ModelChange> changes = computeChanges(repo, previousHead, currentHead);

            // Separate image changes so we can handle them via IArchiveManager
            List<ModelChange> imageChanges = new ArrayList<>();
            List<ModelChange> xmlChanges   = new ArrayList<>();
            String imagesPrefix = IGraficoConstants.IMAGES_FOLDER + "/"; //$NON-NLS-1$
            for(ModelChange change : changes) {
                if(change.gitPath.startsWith(imagesPrefix)) {
                    imageChanges.add(change);
                }
                else {
                    xmlChanges.add(change);
                }
            }

            if(xmlChanges.isEmpty() && imageChanges.isEmpty()) {
                // Only non-model files changed (e.g. README) – nothing to do
                return null;
            }

            // Collect open diagram IDs before we touch the model
            List<String> openDiagramIDs = getOpenDiagramModelIdentifiers(existingModel);

            // Apply XML changes in-place
            GraficoModelImporter importer = new GraficoModelImporter(fRepository.getLocalRepositoryFolder());

            final Exception[] applyException = new Exception[1];
            BusyIndicator.showWhile(Display.getCurrent(), () -> {
                try {
                    importer.applyChangesToModel(existingModel, xmlChanges);
                }
                catch(Exception ex) {
                    applyException[0] = ex;
                }
            });

            if(applyException[0] != null) {
                throw applyException[0];
            }

            // Handle any still-unresolved objects the same way as the full load
            List<UnresolvedObject> unresolved = importer.getUnresolvedObjects();
            IArchimateModel resultModel = existingModel;
            if(unresolved != null) {
                // restoreProblemObjects does a full re-import internally,
                // so we get back a fresh model object in that rare case.
                resultModel = restoreProblemObjects(unresolved);
                resultModel.setFile(fRepository.getTempModelFile());
            }

            // Apply image changes via IArchiveManager
            applyImageChanges(resultModel, imageChanges, repo, currentHead);

            // Save, Close, Re-Open
            IEditorModelManager.INSTANCE.saveModel(resultModel);
            IEditorModelManager.INSTANCE.closeModel(resultModel);
            IEditorModelManager.INSTANCE.openModel(fRepository.getTempModelFile());
            IArchimateModel reopenedModel = fRepository.locateModel();
            if(reopenedModel != null) {
                reopenEditors(reopenedModel, openDiagramIDs);
                resultModel = reopenedModel;
            }

            long timeEnd = System.currentTimeMillis();
            String msg = String.format("*** Model Incrementally Updated in %dms (%d XML changes, %d image changes)", (timeEnd - timeStart), xmlChanges.size(), imageChanges.size());
            System.err.println(msg);
	        ModelRepositoryPlugin.getInstance().getLog().info(msg);

            return resultModel;
        }
    }

    /**
     * Compute the list of ModelChange objects by walking the Git diff between 'before' and 'after' commits.
     */
    private List<ModelChange> computeChanges(Repository repo, ObjectId before, ObjectId after) throws IOException {
        List<ModelChange> changes = new ArrayList<>();

        try(RevWalk rw = new RevWalk(repo);
            TreeWalk tw = new TreeWalk(repo)) {

            tw.addTree(rw.parseCommit(before).getTree());
            tw.addTree(rw.parseCommit(after).getTree());
            tw.setFilter(TreeFilter.ANY_DIFF);
            tw.setRecursive(true);

            while(tw.next()) {
                String path      = tw.getPathString();
                ObjectId beforeId = tw.getObjectId(0);
                ObjectId afterId  = tw.getObjectId(1);

                GraficoModelImporter.ChangeType type;
                if(beforeId.equals(ObjectId.zeroId())) {
                    type = GraficoModelImporter.ChangeType.ADD;
                }
                else if(afterId.equals(ObjectId.zeroId())) {
                    type = GraficoModelImporter.ChangeType.DELETE;
                }
                else {
                    type = GraficoModelImporter.ChangeType.MODIFY;
                }

                changes.add(new ModelChange(type, path));
            }
        }

        return changes;
    }

    /**
     * Apply ADD/MODIFY/DELETE changes to the model's image archive.
     *
     * The image bytes for ADDs and MODIFYs are read directly from the Git object store 
     * so we don't have to worry about the working tree being dirty or out of sync with HEAD
     */
    private void applyImageChanges(IArchimateModel model, List<ModelChange> imageChanges,
                                   Repository repo, ObjectId headCommit) {
        if(imageChanges.isEmpty()) return;

        IArchiveManager archiveManager = (IArchiveManager)model.getAdapter(IArchiveManager.class);
        if(archiveManager == null) return;

        try(RevWalk rw = new RevWalk(repo);
            TreeWalk tw = new TreeWalk(repo)) {

            tw.addTree(rw.parseCommit(headCommit).getTree());
            tw.setRecursive(true);

            // Build a lookup: git path → ObjectId for the current tree
            java.util.Map<String, ObjectId> pathToObjectId = new java.util.HashMap<>();
            while(tw.next()) {
                pathToObjectId.put(tw.getPathString(), tw.getObjectId(0));
            }

            for(ModelChange change : imageChanges) {
                String archivePath = change.gitPath;
                switch(change.type) {
                    case DELETE:
                        // TODO: archiveManager does not have a remove entry method. 
                        break;
                    case ADD:
                    case MODIFY:
                        ObjectId objId = pathToObjectId.get(change.gitPath);
                        if(objId != null) {
                            try {
                                byte[] bytes = repo.open(objId).getBytes();
                                archiveManager.addByteContentEntry(archivePath, bytes);
                            }
                            catch(IOException ex) {
                                ModelRepositoryPlugin.getInstance().getLog().error("Could not load image from Git object store: " + change.gitPath, ex);
                            }
                        }
                        break;
                }
            }
        }
        catch(IOException ex) {
            ModelRepositoryPlugin.getInstance().getLog().error("Error applying image changes", ex); //$NON-NLS-1$
        }
    }

    /**
     * Perform a full model reload from all Grafico XML files.
     * This is the original {@code loadModel()} implementation.
     */
    private IArchimateModel fullLoad() throws IOException {
        // Import Grafico Model
        GraficoModelImporter importer = new GraficoModelImporter(fRepository.getLocalRepositoryFolder());
        
        IArchimateModel[] graficoModel = new IArchimateModel[1];
        IOException[] exception = new IOException[1];
        
        BusyIndicator.showWhile(Display.getCurrent(), () -> {
            try {
                graficoModel[0] = importer.importAsModel();
            }
            catch(IOException ex) {
                exception[0] = ex;
            }
        });
        
        if(exception[0] != null) {
            throw exception[0];
        }
        
        if(graficoModel[0] == null) {
            return null;
        }
        
        // Set file name on the grafico model so we can locate it
        graficoModel[0].setFile(fRepository.getTempModelFile());
        
        // Resolve missing objects
        List<UnresolvedObject> unresolvedObjects = importer.getUnresolvedObjects();
        if(unresolvedObjects != null) {
            graficoModel[0] = restoreProblemObjects(unresolvedObjects);
        }
        
        // Save it
        IEditorModelManager.INSTANCE.saveModel(graficoModel[0]);
        
        // Close and re-open the corresponding model if it is already open
        IArchimateModel model = fRepository.locateModel();
        if(model != null) {
            // Store ids of open diagrams
            List<String> openModelIDs = getOpenDiagramModelIdentifiers(model); // Store ids of open diagrams
            IEditorModelManager.INSTANCE.closeModel(model);
            IEditorModelManager.INSTANCE.openModel(graficoModel[0]);
            reopenEditors(graficoModel[0], openModelIDs);
        }
        
        return graficoModel[0];
    }

    /**
     * @return The list of resolved objects as a message string or null
     */
    public String getRestoredObjectsAsString() {
        if(fRestoredObjects == null) {
            return null;
        }
        
        String s = Messages.GraficoModelLoader_0;
        
        for(IIdentifier id : fRestoredObjects) {
            if(id instanceof INameable) {
                String name = ((INameable)id).getName();
                String className = id.eClass().getName();
                s += "\n" + (StringUtils.isSet(name) ? name + " (" + className + ")" : className); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            }
        }
        
        return s;
    }
    
    /**
     * Find the problem object xml files from the commit history and restore them
     * @param unresolvedObjects 
     * @return
     * @throws IOException
     */
    private IArchimateModel restoreProblemObjects(List<UnresolvedObject> unresolvedObjects) throws IOException {
        fRestoredObjects = new ArrayList<IIdentifier>();
        
        List<String> restoredIdentifiers = new ArrayList<String>();
        
        try(Repository repository = Git.open(fRepository.getLocalRepositoryFolder()).getRepository()) {
            try(RevWalk revWalk = new RevWalk(repository)) {
                for(UnresolvedObject unresolved : unresolvedObjects) {
                    String missingFileName = unresolved.missingObjectURI.lastSegment();
                    String missingObjectID = unresolved.missingObjectURI.fragment();
                    
                    // Already got this one
                    if(restoredIdentifiers.contains(missingObjectID)) {
                        continue;
                    }
                    
                    boolean found = false;
                    
                    // Reset RevWalk
                    revWalk.reset();
                    ObjectId id = repository.resolve(IGraficoConstants.HEAD);
                    if(id != null) {
                        revWalk.markStart(revWalk.parseCommit(id)); 
                    }
                    
                    // Iterate all commits
                    for(RevCommit commit : revWalk ) {
                        try(TreeWalk treeWalk = new TreeWalk(repository)) {
                            treeWalk.addTree(commit.getTree());
                            treeWalk.setRecursive(true);
                            
                            // Iterate through all files
                            // We can't use a PathFilter for the file name as its path is not correct
                            while(!found && treeWalk.next()) {
                                // File is found
                                if(treeWalk.getPathString().endsWith(missingFileName)) {
                                    // Save file
                                    ObjectId objectId = treeWalk.getObjectId(0);
                                    ObjectLoader loader = repository.open(objectId);

                                    File file = new File(fRepository.getLocalRepositoryFolder(), treeWalk.getPathString());
                                    file.getParentFile().mkdirs();
                                    
                                    try(FileOutputStream out = new FileOutputStream(file)) {
                                        loader.copyTo(out);
                                    }
                                    
                                    restoredIdentifiers.add(missingObjectID);
                                    found = true;
                                }
                            }
                        }
                        
                        if(found) {
                            break;
                        }
                    }
                }
                
                revWalk.dispose();
            }
        }
        
        // Then re-import
        GraficoModelImporter importer = new GraficoModelImporter(fRepository.getLocalRepositoryFolder());
        IArchimateModel graficoModel = importer.importAsModel();
        graficoModel.setFile(fRepository.getTempModelFile()); // do this again
        
        // Collect restored objects
        for(Iterator<EObject> iter = graficoModel.eAllContents(); iter.hasNext();) {
            EObject element = iter.next();
            for(String id : restoredIdentifiers) {
                if(element instanceof IIdentifier && id.equals(((IIdentifier)element).getId())) {
                    fRestoredObjects.add((IIdentifier)element);
                }
            }
        }
        
        return graficoModel;
    }

    @SuppressWarnings("unused")
    private void deleteProblemObjects(List<UnresolvedObject> unresolvedObjects, IArchimateModel model) throws IOException {
        for(UnresolvedObject unresolved : unresolvedObjects) {
            String parentID = unresolved.parentObject.getId();
            
            EObject eObject = ArchimateModelUtils.getObjectByID(model, parentID);
            if(eObject != null) {
                EcoreUtil.remove(eObject);
            }
        }
        
        // And re-export to grafico xml files
        GraficoModelExporter exporter = new GraficoModelExporter(model, fRepository.getLocalRepositoryFolder());
        exporter.exportModel();
    }

    /**
     * @param model
     * @return All open diagram models' ids so we can restore them
     */
    private List<String> getOpenDiagramModelIdentifiers(IArchimateModel model) {
        List<String> list = new ArrayList<String>();
        
        for(IEditorReference ref : PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage().getEditorReferences()) {
            try {
                IEditorInput input = ref.getEditorInput();
                if(input instanceof DiagramEditorInput) {
                    IDiagramModel dm = ((DiagramEditorInput)input).getDiagramModel();
                    if(dm.getArchimateModel() == model) {
                        list.add(dm.getId());
                    }
                }
            }
            catch(PartInitException ex) {
                ex.printStackTrace();
            }
        }
        
        return list;
    }
    
    /**
     * Re-open any diagram editors
     * @param model
     * @param ids
     */
    private void reopenEditors(IArchimateModel model, List<String> ids) {
        if(ids != null) {
            for(String id : ids) {
                EObject eObject = ArchimateModelUtils.getObjectByID(model, id);
                if(eObject instanceof IDiagramModel) {
                    EditorManager.openDiagramEditor((IDiagramModel)eObject);
                }
            }
        }
    }

}