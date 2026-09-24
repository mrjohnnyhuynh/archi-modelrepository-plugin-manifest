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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.archicontribs.modelrepository.ModelRepositoryPlugin;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.common.util.URI;
import org.eclipse.emf.ecore.EObject;
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
import com.archimatetool.model.IDiagramModelArchimateComponent;
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
        /**
         * True if the importer has already fixed up the dangling reference itself in-place
         * (e.g. dropping a single unresolved profile entry from its concept's profile list)
         * rather than deferring full removal of parentObject to the caller.
         */
        boolean handledDuringImport;

        UnresolvedObject(URI missingObjectURI, IIdentifier parentObject) {
            this(missingObjectURI, parentObject, false);
        }

        UnresolvedObject(URI missingObjectURI, IIdentifier parentObject, boolean handledDuringImport) {
            this.missingObjectURI = missingObjectURI;
            this.parentObject = parentObject;
            this.handledDuringImport = handledDuringImport;
        }
    }
    
	// ID -> Object lookup table
    private Map<String, IIdentifier> fIDLookup;
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
    	
    	// read all XML files in parallel using NIO
    	fFileCache = new ConcurrentHashMap<>();
    	List<Path> xmlPaths = Files.walk(modelFolder.toPath())
    	    .filter(p -> p.toString().endsWith(".xml")) //$NON-NLS-1$
    	    .collect(java.util.stream.Collectors.toList());

    	xmlPaths.parallelStream().forEach(path -> {
    	    try {
    	        fFileCache.put(path.toFile(), Files.readAllBytes(path));
    	    }
    	    catch(IOException ex) {
    	        ModelRepositoryPlugin.getInstance().getLog().error("Could not pre-load file: " + path, ex); //$NON-NLS-1$
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
            ModelRepositoryPlugin.getInstance().getLog().error("Error loading model", ex); //$NON-NLS-1$
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
	            		int unresolvedCountBefore = fUnresolvedObjects == null ? 0 : fUnresolvedObjects.size();
	            		IProfile resolved = (IProfile)resolve(profile, concept);
	            		boolean wasUnresolved = fUnresolvedObjects != null && fUnresolvedObjects.size() > unresolvedCountBefore;
	            		if(wasUnresolved) {
	            		    // A missing profile is a dangling reference on this single list
	            		    // entry only. Removing the whole concept (the recorded parentObject)
	            		    // because it lost a style/tag would be far too destructive, so drop
	            		    // just the broken profile reference here and mark it as already
	            		    // handled so the caller doesn't also try to remove the concept.
	            		    fUnresolvedObjects.get(fUnresolvedObjects.size() - 1).handledDuringImport = true;
	            		    iterator.remove();
	            		}
	            		else {
	            		    iterator.set(resolved);
	            		}
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

        // A diagram connection stores both its visual endpoints and a reference
        // to the model relationship. Once all proxies have been resolved, make
        // the relationship ends agree with those visual endpoints before the
        // model is validated or serialized.
        //
        // Deliberately not using IDiagramModelConnection#reconnect() here: its
        // default implementation also re-adds the connection to its source/target
        // connection lists (source.addConnection()/target.addConnection()), which
        // mutates the diagram's containment tree. Doing that during import - before
        // the resource is fully settled - was observed to corrupt later
        // serialization (same-document connections were written out as verbose
        // cross-file href references instead of plain same-document id attributes).
        // Only the relationship endpoint sync is needed here, so it is replicated
        // directly without the connection list side effect.
        for(Iterator<EObject> iter = fModel.eAllContents(); iter.hasNext();) {
            EObject eObject = iter.next();
            if(eObject instanceof IDiagramModelArchimateConnection connection
                    && connection.getSource() instanceof IDiagramModelArchimateComponent sourceComponent
                    && connection.getTarget() instanceof IDiagramModelArchimateComponent targetComponent
                    && connection.getArchimateRelationship() != null) {
                IArchimateConcept sourceConcept = sourceComponent.getArchimateConcept();
                IArchimateConcept targetConcept = targetComponent.getArchimateConcept();

                // If either endpoint's concept is still an unresolved proxy (its
                // reference could not be found in this Grafico checkout), do not
                // write it onto the relationship: the relationship is the model's
                // canonical source of truth and may be shared by several diagrams,
                // so overwriting a good endpoint with a dangling proxy here would
                // corrupt the relationship for every other diagram that uses it.
                if(sourceConcept == null || sourceConcept.eIsProxy() || targetConcept == null || targetConcept.eIsProxy()) {
                    continue;
                }

                connection.getArchimateRelationship().setSource(sourceConcept);
                connection.getArchimateRelationship().setTarget(targetConcept);
            }
        }
    }

    /**
     * Check if 'object' is a proxy. if yes, replace it with real object from mapping table.
     */
    private EObject resolve(IIdentifier object, IIdentifier parent) {
        if(object != null && object.eIsProxy()) {
            URI objectURI = EcoreUtil.getURI(object);
            String objectID = EcoreUtil.getURI(object).fragment();
            
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
        else {
            return object;
        }
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
                if(fileOrFolder.isFile()) {
                    if(!fileOrFolder.getName().equals(IGraficoConstants.FOLDER_XML) && fileOrFolder.getName().endsWith(".xml")) { //$NON-NLS-1$
                        currentFolder.getElements().add(loadElement(fileOrFolder));
                    }
                }
                else {
                    currentFolder.getFolders().add(loadFolder(fileOrFolder));
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
        
        // Use pre-loaded bytes if available 
        byte[] bytes = fFileCache != null ? fFileCache.get(file) : null;
        if(bytes != null) {
            // Use pre-loaded bytes to avoid a new FileInputStream per file
            eObject = GraficoResourceLoader.loadEObject(new java.io.ByteArrayInputStream(bytes));
        }
        else {
            // Fallback for any file not in cache (shouldn't happen, but safe)
            eObject = GraficoResourceLoader.loadEObject(file);
        }

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
