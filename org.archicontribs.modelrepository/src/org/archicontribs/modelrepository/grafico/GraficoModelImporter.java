/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.grafico;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.ecore.xmi.impl.XMLResourceImpl;
import org.eclipse.gef.commands.CommandStack;

import com.archimatetool.editor.model.IArchiveManager;
import com.archimatetool.editor.model.compatibility.CompatibilityHandlerException;
import com.archimatetool.editor.model.compatibility.ModelCompatibility;
import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateConcept;
import com.archimatetool.model.IArchimateElement;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IArchimateRelationship;
import com.archimatetool.model.IDiagramModel;
import com.archimatetool.model.IDiagramModelArchimateConnection;
import com.archimatetool.model.IDiagramModelArchimateObject;
import com.archimatetool.model.IDiagramModelReference;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;
import com.archimatetool.model.IProfile;



/**
 * Based on the GRAFICO Model Importer
 * GRAFICO (Git fRiendly Archi FIle COllection) is a way to persist an ArchiMate
 * model in a bunch of XML files (one file per ArchiMate element or view).
 * 
 * @author Jean-Baptiste Sarrodie
 * @author Quentin Varquet
 * @author Phillip Beauvoir
 */
public class GraficoModelImporter {
    
    /**
     * Unresolved missing object class
     * 
     * @author Phillip Beauvoir
     */
    static class UnresolvedObject {
        URI missingObjectURI;
        IIdentifier parentObject;

        UnresolvedObject(URI missingObjectURI, IIdentifier parentObject) {
            this.missingObjectURI = missingObjectURI;
            this.parentObject = parentObject;
        }
    }

    // Track GIT Changes
    public enum ChangeType { ADD, MODIFY, DELETE }

    public static class ModelChange {
        public final ChangeType type;
        public final String gitPath;

        public ModelChange(ChangeType type, String gitPath) {
            this.type = type;
            this.gitPath = gitPath;
        }
    }
    
    // Lookup table of ID -> Object to resolve proxies. Populated at import; updated when applying incremental changes.
    private Map<String, IIdentifier> fIDLookup;
    
    // File cache for pre-loading XML files in parallel using NIO
    private Map<File, byte[]> fFileCache;
    
    /**
     * Unresolved missing objects
     */
    private List<UnresolvedObject> fUnresolvedObjects;
    
    /**
     * Model
     */
    private IArchimateModel fModel;
    
    /**
     * Local repo folder
     */
    private File fLocalRepoFolder;
    
    /**
     * @param folder The folder containing the grafico XML files
     */
    public GraficoModelImporter(File folder) {
        if(folder == null) {
            throw new IllegalArgumentException("Folder cannot be null"); //$NON-NLS-1$
        }
        
        fLocalRepoFolder = folder;
    }
	
    /**
     * Import the grafico XML files as a IArchimateModel
     * @throws IOException
     */
    public IArchimateModel importAsModel() throws IOException {
    	// Create folders for model and images
    	File modelFolder = new File(fLocalRepoFolder, IGraficoConstants.MODEL_FOLDER);
        modelFolder.mkdirs();

        File imagesFolder = new File(fLocalRepoFolder, IGraficoConstants.IMAGES_FOLDER);
    	imagesFolder.mkdirs();
    	
    	// If the top folder.xml does not exist then there is nothing to import, so return null
    	if(!(new File(modelFolder, IGraficoConstants.FOLDER_XML)).isFile()) {
    	    return null;
    	}
    	
    	// Reset the ID -> Object lookup table
    	fIDLookup = new HashMap<String, IIdentifier>();
    	
    	// Populate the File Cache: read all XML files in parallel using NIO
    	fFileCache = new ConcurrentHashMap<>();
    	List<Path> xmlPaths = Files.walk(modelFolder.toPath())
    	    .filter(p -> p.toString().endsWith(".xml")) //$NON-NLS-1$
    	    .collect(java.util.stream.Collectors.toList());

    	xmlPaths.parallelStream().forEach(path -> {
    	    try {
    	        fFileCache.put(path.toFile(), Files.readAllBytes(path));
    	    }
    	    catch(IOException ex) {
    	        ModelRepositoryPlugin.getInstance().getLog().error("Could not pre-load file: " + path, ex);
    	    }
    	});
    	
        // Load the Model from files (it will contain unresolved proxies)
    	fModel = loadModel(modelFolder);
    	fFileCache = null; // Clear file cache to free up memory
    	
    	// Create a new Resource for the model object so we can work with it in the ModelCompatibility class
    	Resource resource = new XMLResourceImpl();
    	resource.getContents().add(fModel);
    	
        // Resolve proxies
        resolveProxies();

    	// New model compatibility
        ModelCompatibility modelCompatibility = new ModelCompatibility(resource);
    	
        // Fix any backward compatibility issues
    	// This has to be done here because GraficoModelLoader#loadModel() will save with latest metamodel version number
    	// And then the ModelCompatibility won't be able to tell the version number
        try {
            modelCompatibility.fixCompatibility();
        }
        catch(CompatibilityHandlerException ex) {
            ModelRepositoryPlugin.getInstance().getLog().error("Error loading model", ex);
        }

    	// We now have to remove the Eobject from its Resource so it can be saved in its proper *.archimate format
        resource.getContents().remove(fModel);
        
        // Add Archive Manager and CommandStack
        IArchiveManager archiveManager = IArchiveManager.FACTORY.createArchiveManager(fModel);
        fModel.setAdapter(IArchiveManager.class, archiveManager);
        
        // We do need a CommandStack for ACLI
        CommandStack cmdStack = new CommandStack();
        fModel.setAdapter(CommandStack.class, cmdStack);
        
    	// Load images
    	loadImages(imagesFolder, archiveManager);

    	return fModel;
    }

    /**
     * Apply a list of model changes (e.g. found via git diff) to the given model; in six steps:
     * 
     *  1. Track existing IDs and objects 
     *  2. Separate changes into MODIFYs, Element/Folder DELETEs, Folder/Element ADDs (in that order)
     *  3. DELETEs – elements first, then folders. Children before parents. i.e. Deepest-first
     *  4. ADDs – folders first, then element. Parents before children. i.e. Shallow-first
     *  5. MODIFYs – load the file and copy all EMF features onto the existing live object (to maintain references)
     *  6. Resolve cross-references 
     * 
     */
    public void applyChangesToModel(IArchimateModel model, List<ModelChange> changes) throws IOException {
        fModel = model;
        fFileCache = null; // incremental path loads files individually – no bulk pre-load needed

        // 1. Track existing IDs and objects in a lookup table for quick resolution during MODIFY 
        fIDLookup = new HashMap<>();
        fIDLookup.put(model.getId(), model);
        for(IProfile profile : model.getProfiles()) {
            fIDLookup.put(profile.getId(), profile);
        }
        for(Iterator<EObject> iter = model.eAllContents(); iter.hasNext();) {
            EObject obj = iter.next();
            if(obj instanceof IIdentifier) {
                fIDLookup.put(((IIdentifier)obj).getId(), (IIdentifier)obj);
            }
        }

        // 2. Separate Changes into MODIFYs, Element/Folder DELETEs, Folder/Element ADDs (in that order)
        List<ModelChange> folderAdds    = new ArrayList<>();
        List<ModelChange> elementAdds   = new ArrayList<>();
        List<ModelChange> modified      = new ArrayList<>();
        List<ModelChange> folderDeletes = new ArrayList<>();
        List<ModelChange> elementDeletes = new ArrayList<>();

        for(ModelChange change : changes) {
            boolean isFolderXml = change.gitPath.endsWith(IGraficoConstants.FOLDER_XML);
            switch(change.type) {
                case ADD:
                    if(isFolderXml) folderAdds.add(change);
                    else            elementAdds.add(change);
                    break;
                case DELETE:
                    if(isFolderXml) folderDeletes.add(change);
                    else            elementDeletes.add(change);
                    break;
                case MODIFY:
                    modified.add(change);
                    break;
            }
        }

        // 3. DELETEs – elements first, then folders. Children before parents. i.e. Deepest-first
        for(ModelChange change : elementDeletes) {
            String id = extractIdFromPath(change.gitPath);
            IIdentifier obj = fIDLookup.remove(id);
            if(obj != null) {
            	// System.err.println("Deleting element: " + change.gitPath);
            	EcoreUtil.remove((EObject)obj);
            }
        }

        // Sort folders, using '/', in reverse, to delete based on depth 
        folderDeletes.sort(Comparator.comparingLong((ModelChange c) -> c.gitPath.chars().filter(ch -> ch == '/').count()).reversed()); 
        
        for(ModelChange change : folderDeletes) {
            //String id = extractIdFromXml(change.gitPath);
            String id = extractIdFromPath(change.gitPath);
            if(id == null) continue; // top-level folder.xml – should never be deleted
            IIdentifier obj = fIDLookup.remove(id);
            if(obj != null) {
            	// System.err.println("Deleting folder: " + change.gitPath);
                EcoreUtil.remove((EObject)obj);
            }
        }

        // 4. ADDs – folders first, then element. Parents before children. i.e. Shallow-first
        folderAdds.sort(Comparator.comparingLong((ModelChange c) -> c.gitPath.chars().filter(ch -> ch == '/').count()));

        for(ModelChange change : folderAdds) {
            File file = new File(fLocalRepoFolder, change.gitPath);
            IFolder newFolder = (IFolder)loadElement(file); // registers in fIDLookup
            IFolder parent = findParentFolder(model, change.gitPath);
            if(parent != null) {
            	// System.err.println("Adding folder: " + change.gitPath + " to parent: " + parent.getName());
                parent.getFolders().add(newFolder);
            }
            else {
                ModelRepositoryPlugin.getInstance().getLog().error("Could not find parent folder for ADD: " + change.gitPath, null);
            }
        }

        for(ModelChange change : elementAdds) {
            File file = new File(fLocalRepoFolder, change.gitPath);
            EObject newElement = loadElement(file); // registers in fIDLookup
            IFolder parent = findParentFolder(model, change.gitPath);
            if(parent != null) {
            	// System.err.println("Adding element: " + change.gitPath + " to parent: " + parent.getName());
                parent.getElements().add(newElement);
            }
            else {
                ModelRepositoryPlugin.getInstance().getLog().error("Could not find parent folder for ADD: " + change.gitPath, null);
            }
        }

        // 5. MODIFYs – load the file and copy all EMF features onto the existing live object (to maintain references)
        for(ModelChange change : modified) {
            File file = new File(fLocalRepoFolder, change.gitPath);
            String id = extractIdFromXml(change.gitPath);

            if(id == null) continue;

            IIdentifier existing = fIDLookup.get(id);
            if(existing == null) {
            	ModelRepositoryPlugin.getInstance().getLog().error("Could not find existing object for MODIFY: " + change.gitPath, null);
                continue;
            }

            EObject newObj = loadElement(file);

            if(existing != newObj) {
            	// System.err.println("Modifying element: " + change.gitPath);
                boolean isDiagram = existing instanceof IDiagramModel;
                copyAllFeatures(newObj, (EObject)existing);
                if(isDiagram) {
                    // Re-register new diagram children so resolveProxies() can find them.
                    for(Iterator<EObject> it = ((EObject)existing).eAllContents(); it.hasNext();) {
                        EObject child = it.next();
                        if(child instanceof IIdentifier) {
                            fIDLookup.put(((IIdentifier)child).getId(), (IIdentifier)child);
                        }
                    }
                }
                fIDLookup.put(id, existing);
            }
        }

        // 6 - Resolve cross-references / proxies after all changes are applied
        resolveProxies();
    }

    // Helpers for incremental update Start

    /**
     * Copy all non-derived, non-transient, changeable EMF features from source to target.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void copyAllFeatures(EObject source, EObject target) {
        for(EStructuralFeature feature : source.eClass().getEAllStructuralFeatures()) {
            if(feature.isDerived() || feature.isTransient() || !feature.isChangeable()) {
                continue;
            }
            if(feature.isMany()) {
                EList sourceList = (EList)source.eGet(feature);
                EList targetList = (EList)target.eGet(feature);
                targetList.clear();
                // Snapshot the source list: for containment features, addAll
                // moves children (changing their container), which would modify
                // the live sourceList during iteration without a copy.
                targetList.addAll(new ArrayList<>(sourceList));
            }
            else {
                // Preserve the eIsSet state
                if(source.eIsSet(feature)) {
                    target.eSet(feature, source.eGet(feature));
                } else {
                    target.eUnset(feature);
                }
            }
        }
    }
 
    /**
     * Given an XML, extract the element ID from the "id" attribute
     */
    private String extractIdFromXml(String gitPath) {
        if (gitPath == null || gitPath.isEmpty() ) {
            return null;
        }

        try {
        	Path file = new File(fLocalRepoFolder, gitPath).toPath();
            if (!Files.exists(file) || !Files.isRegularFile(file)) {
                return null;
            }

            XMLInputFactory factory = XMLInputFactory.newFactory();
            try (InputStream in = Files.newInputStream(file)) {
                XMLStreamReader reader = factory.createXMLStreamReader(in);

                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String id = reader.getAttributeValue(null, "id");
                        if (id != null && !id.isEmpty()) {
                            return id;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // swallow and return null
        }

        return null;
    }

    /**
     * Given an XML, extract the element ID from the filename. 
     */
    private String extractIdFromPath(String gitPath) {
        String filename = gitPath.substring(gitPath.lastIndexOf('/') + 1);
        if (filename.endsWith(".xml")) {
            filename = filename.substring(0, filename.length() - 4);
        }

        // folder.xml - use parent directory name
        if (filename.equals("folder")) {
            int slash = gitPath.lastIndexOf('/');
            if (slash > 0) {
                return gitPath.substring(0, slash).substring(gitPath.substring(0, slash).lastIndexOf('/') + 1);
            }
            return null;
        }

        // Element XML - use the part after the last underscore
        int idx = filename.lastIndexOf('_');
        return (idx != -1) ? filename.substring(idx + 1) : filename;
    }

    /**
     * Locate the parent folder for a file at the given gitPath.
     * This is needed for ADD and MODIFY operations to know where to attach the loaded object in the model.
     *
     * The parent folder is determined by the directory structure of the gitPath:
     * For a regular element XML file, the parent is the folder whose directory matches the path up to the element file.
     * For a new folder (identified by folder.xml), the parent is the folder one level up from the new folder's directory, since the new folder itself doesn't exist yet.
     */
    private IFolder findParentFolder(IArchimateModel model, String gitPath) {
        boolean isFolderXml = gitPath.endsWith(IGraficoConstants.FOLDER_XML);

        String dirPath;
        int lastSlash = gitPath.lastIndexOf('/');
        String withoutFilename = gitPath.substring(0, lastSlash); // strip filename
        
        // For folder.xml, strip the folder being added as well to get the parent directory
        if(isFolderXml) {
            int secondLastSlash = withoutFilename.lastIndexOf('/');
            dirPath = (secondLastSlash >= 0) ? withoutFilename.substring(0, secondLastSlash) : "";
        }
        // For element XML, the parent directory is just the path without the filename
        else {
            dirPath = withoutFilename;
        }

        if(dirPath.isEmpty()) return null;

        String[] parts = dirPath.split("/");

        // The first part is the top-level "model" folder, so skip it
        if(parts.length < 2) return null;

        // The second part is a top-level folder, with a FolderType name
        IFolder folder = null;
        for(IFolder f : model.getFolders()) {
            if(f.getType().toString().equalsIgnoreCase(parts[1])) {
                folder = f;
                break;
            }
        }
        if(folder == null) return null;

        // walk the sub-folders, the folder names are IDs
        for(int i = 2; i < parts.length; i++) {
            IIdentifier sub = fIDLookup.get(parts[i]);
            if(!(sub instanceof IFolder)) return null;
            folder = (IFolder)sub;
        }

        return folder;
    }
    
    // Helpers for incremental update End

    /**
     * @return A list of unresolved objects. Can be null if no unresolved objects
     */
    public List<UnresolvedObject> getUnresolvedObjects() {
        return fUnresolvedObjects;
    }
    
    /**
     * Read images from images subfolder and load them into the model
     */
    private void loadImages(File folder, IArchiveManager archiveManager) {
        File[] files = folder.listFiles();
        if(files == null) {
            return;
        }
        
        for(File imageFile : files) {
            if(imageFile.isFile()) {
                try {
                    byte[] bytes = Files.readAllBytes(imageFile.toPath());
                    // This must match the prefix used in ArchiveManager.createArchiveImagePathname()
                    archiveManager.addByteContentEntry("images/" + imageFile.getName(), bytes); //$NON-NLS-1$
                }
                // Catch exception here and continue on to next image
                // Don't fail loading the model because of an image
                catch(IOException ex) {
                    ModelRepositoryPlugin.getInstance().getLog().error("Could not load image", ex); //$NON-NLS-1$
                }
            }
        }
    }    
   
    /**
     * Iterate through all model objects, and resolve proxies on known classes
     */
    private void resolveProxies() {
        fUnresolvedObjects = null;
        
        for(Iterator<EObject> iter = fModel.eAllContents(); iter.hasNext();) {
            EObject eObject = iter.next();

            if(eObject instanceof IArchimateConcept) {
                // Resolve proxies for profiles
            	IArchimateConcept concept = (IArchimateConcept)eObject;
            	EList<IProfile> profiles = concept.getProfiles();
            	// getProfiles() can't return null so no need to check
            	// Assumption: most concepts don't have profiles so checking for empty has a positive impact on performance
            	if(!profiles.isEmpty()) {
	            	ListIterator<IProfile> iterator = profiles.listIterator();
	            	while(iterator.hasNext()) {
	            		IProfile profile = iterator.next();
	            		iterator.set((IProfile)resolve(profile, concept));
	            	}
            	}
            }
            
            if(eObject instanceof IArchimateRelationship) {
                // Resolve proxies for Relations
                IArchimateRelationship relation = (IArchimateRelationship)eObject;
                relation.setSource((IArchimateConcept)resolve(relation.getSource(), relation));
                relation.setTarget((IArchimateConcept)resolve(relation.getTarget(), relation));
            }
            else if(eObject instanceof IDiagramModelArchimateObject) {
                // Resolve proxies for Elements
                IDiagramModelArchimateObject element = (IDiagramModelArchimateObject)eObject;
                element.setArchimateElement((IArchimateElement)resolve(element.getArchimateElement(), element));
            }
            else if(eObject instanceof IDiagramModelArchimateConnection) {
                // Resolve proxies for Connections
                IDiagramModelArchimateConnection archiConnection = (IDiagramModelArchimateConnection)eObject;
                archiConnection.setArchimateRelationship((IArchimateRelationship)resolve(archiConnection.getArchimateRelationship(), archiConnection));
            }
            else if(eObject instanceof IDiagramModelReference) {
                // Resolve proxies for Model References
                IDiagramModelReference element = (IDiagramModelReference)eObject;
                element.setReferencedModel((IDiagramModel)resolve(element.getReferencedModel(), element));
            }
        }
    }

    /**
     * Check if 'object' is a proxy or a stale direct reference, and replace it with the canonical object from the mapping table.
     * 3 Scenarios can cause an object reference to be unresolved:
     * 
     *  1. reference is a proxy (i.e. freshly-loaded XML during a full reload)
     *  2. stale reference to an object that was deleted by a remote commit
     *  3. stale reference to an object that was replaced by a move (DELETE+ADD)
     *   
     */
    private EObject resolve(IIdentifier object, IIdentifier parent) {
        if(object == null) {
            return null;
        }

        // EMF proxy resolution for full reload: look up the canonical object in the mapping table and return it if found; otherwise add to unresolved list
        if(object.eIsProxy()) {
            URI objectURI = EcoreUtil.getURI(object);
            String objectID = objectURI.fragment();

            // Get proxy object
            IIdentifier newObject = fIDLookup.get(objectID);

            // If proxy has not been resolved
            if(newObject == null) {
                // Add to list
                if(fUnresolvedObjects == null) {
                    fUnresolvedObjects = new ArrayList<UnresolvedObject>();
                }
                fUnresolvedObjects.add(new UnresolvedObject(objectURI, parent));
            }

            return newObject == null ? object : newObject;
        }

        // Non-proxy, stale reference 
        // look up the canonical object in the mapping table and return it if found;
        IIdentifier canonical = fIDLookup.get(object.getId());
        if(canonical != null && canonical != object) {
            return canonical;
        }

        // Object was deleted — return the (now-detached) reference as-is.
        // Any remaining direct references to it are harmless and will be cleaned up on the next full reload.
        return object;
    }
    
	private IArchimateModel loadModel(File folder) throws IOException {
		IArchimateModel model = (IArchimateModel)loadElement(new File(folder, IGraficoConstants.FOLDER_XML));
		IFolder tmpFolder;
		
		List<FolderType> folderList = new ArrayList<FolderType>();
		folderList.add(FolderType.STRATEGY);
		folderList.add(FolderType.BUSINESS);
		folderList.add(FolderType.APPLICATION);
		folderList.add(FolderType.TECHNOLOGY);
		folderList.add(FolderType.MOTIVATION);
		folderList.add(FolderType.IMPLEMENTATION_MIGRATION);
		folderList.add(FolderType.OTHER);
		folderList.add(FolderType.RELATIONS);
		folderList.add(FolderType.DIAGRAMS);

		// Loop based on FolderType enumeration
		for(FolderType folderType : folderList) {
		    if((tmpFolder = loadFolder(new File(folder, folderType.toString()))) != null) {
		        model.getFolders().add(tmpFolder);
		    }
		}
		
		return model;
	}
	
	/**
	 * Load each XML file to recreate original object
	 * 
	 * @param folder
	 * @return Model folder
	 * @throws IOException 
	 */
    private IFolder loadFolder(File folder) throws IOException {
        if(!folder.isDirectory() || !(new File(folder, IGraficoConstants.FOLDER_XML)).isFile()) {
            throw new IOException("File is not directory or folder.xml does not exist."); //$NON-NLS-1$
        }

        // Load folder object itself
        IFolder currentFolder = (IFolder)loadElement(new File(folder, IGraficoConstants.FOLDER_XML));

        // Load each elements (except folder.xml) and add them to folder
        File[] files = folder.listFiles();
        if(files != null) {
            for(File fileOrFolder : files) {
                if(!fileOrFolder.getName().equals(IGraficoConstants.FOLDER_XML)) {
                    if(fileOrFolder.isFile()) {
                        currentFolder.getElements().add(loadElement(fileOrFolder));
                    }
                    else {
                        currentFolder.getFolders().add(loadFolder(fileOrFolder));
                    }
                }
            }
        }

        return currentFolder;
    }

    /**
     * Create an eObject from an XML file. Basically load a resource.
     * 
     * @param file
     * @return
     * @throws IOException 
     */
    private EObject loadElement(File file) throws IOException {
    	IIdentifier eObject;
    	byte[] bytes;
        
        // Use pre-loaded bytes if available; otherwise read from disk. 
    	if ( fFileCache != null) {
    		bytes = fFileCache.get(file);
		} else {
			bytes = Files.readAllBytes(file.toPath());
		}
        
        eObject = GraficoResourceLoader.loadEObject(new java.io.ByteArrayInputStream(bytes));

        // Update an ID -> Object mapping table (used as a cache to resolve proxies)
        fIDLookup.put(eObject.getId(), eObject);
        if(eObject instanceof IArchimateModel) {
        	for(IProfile profile : ((IArchimateModel)eObject).getProfiles()) {
        		fIDLookup.put(profile.getId(), profile);
        	}
        }

        return eObject;
    }
}