/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.archicontribs.modelrepository.grafico.GraficoModelImporter.UnresolvedObject;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.swt.custom.BusyIndicator;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;

import com.archimatetool.editor.diagram.DiagramEditorInput;
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
     * Load the model
     * @return
     * @throws IOException
     */
    public IArchimateModel loadModel() throws IOException {
        fRestoredObjects = null;
        
        // DEBUG - time how long it takes to load the model from Grafico files
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
            deleteProblemObjects(unresolvedObjects, graficoModel[0]);
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
        
        // Manifest DEBUG
        
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
     * Remove diagram objects/references whose underlying model concept could not be resolved
     * (a "dangling" cross-file reference), and re-export so the Grafico files on disk reflect
     * the cleaned-up model.
     * <p>
     * This used to instead search the ENTIRE commit history for a file matching the missing
     * object's file name and blindly copy its bytes back into the working tree ("resurrecting"
     * it). That was unsafe: a dangling reference can be left behind long after the referenced
     * object was legitimately deleted (e.g. deleting an element but not the diagram objects that
     * still pointed at it), and on the next import that stale reference would cause the deleted
     * object - and anything else sharing its file name - to reappear from history, producing a
     * large, unexpected diff and a "phantom" commit prompt on the next Refresh. Removing the
     * dangling reference instead is safe and matches what the user actually intended (the object
     * really was deleted).
     * @param unresolvedObjects
     * @param model
     * @throws IOException
     */
    private void deleteProblemObjects(List<UnresolvedObject> unresolvedObjects, IArchimateModel model) throws IOException {
        fRestoredObjects = new ArrayList<IIdentifier>();
        
        List<String> removedParentIDs = new ArrayList<String>();
        boolean modelChanged = false;
        
        for(UnresolvedObject unresolved : unresolvedObjects) {
            // Some unresolved references (e.g. a single dangling profile entry) are already
            // fixed up in-place by the importer itself, since removing their recorded
            // parentObject entirely would be far more destructive than necessary. Nothing
            // more to remove here, but the model did change and still needs re-exporting.
            if(unresolved.handledDuringImport) {
                modelChanged = true;
                continue;
            }
            
            String parentID = unresolved.parentObject.getId();
            
            // Already removed this one
            if(removedParentIDs.contains(parentID)) {
                continue;
            }
            
            EObject eObject = ArchimateModelUtils.getObjectByID(model, parentID);
            if(eObject != null) {
                if(eObject instanceof IIdentifier) {
                    fRestoredObjects.add((IIdentifier)eObject);
                }
                EcoreUtil.remove(eObject);
                removedParentIDs.add(parentID);
                modelChanged = true;
            }
        }
        
        // Re-export to Grafico xml files so the dangling reference is actually removed on disk
        if(modelChanged) {
            GraficoModelExporter exporter = new GraficoModelExporter(model, fRepository.getLocalRepositoryFolder());
            exporter.exportModel();
        }
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
