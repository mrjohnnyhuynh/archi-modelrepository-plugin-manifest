package org.archicontribs.modelrepository.grafico;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.emf.ecore.EObject;

import com.archimatetool.model.FolderType;
import com.archimatetool.model.IArchimateFactory;
import com.archimatetool.model.IArchimateModel;
import com.archimatetool.model.IFolder;
import com.archimatetool.model.IIdentifier;

/**
 * Implementation of folder finding/creation based on element's file path.
 * 
 * In Grafico, the file path structure mirrors the model's folder structure:
 * 
 * File system:                          Model structure:
 * model/                                IArchimateModel
 *   business/                             Folder (Business)
 *     actors/                               Folder (Actors)
 *       BusinessActor_id-123.xml              BusinessActor element
 *     processes/                            Folder (Processes)
 *       BusinessProcess_id-456.xml            BusinessProcess element
 *   application/                          Folder (Application)
 *     ApplicationComponent_id-789.xml       ApplicationComponent element
 * 
 * The file path tells us exactly which folder the element belongs to!
 */
public class FolderFinder {
    
    /**
     * Find or create the appropriate folder for an element based on its file path.
     * 
     * @param model The Archimate model
     * @param element The element to find a folder for
     * @param xmlFilePath The original file path (e.g., "model/business/actors/BusinessActor_id-123.xml")
     * @return The folder where this element should be placed
     */
    public IFolder findOrCreateFolderForElement(
            IArchimateModel model, 
            EObject element, 
            String xmlFilePath) {
        
        // Parse the folder path from the XML file path
        List<String> folderPath = parseFolderPath(xmlFilePath);
        
        if (folderPath.isEmpty()) {
            // No folder path - return root folder based on element type
            return getRootFolderForElement(model, element);
        }
        
        // Navigate/create folder structure
        return findOrCreateFolderByPath(model, folderPath);
    }
    
    /**
     * Parse the folder path from an XML file path.
     * 
     * Examples:
     * - "model/business/actors/BusinessActor_id-123.xml" -> ["business", "actors"]
     * - "model/application/ApplicationComponent_id-456.xml" -> ["application"]
     * - "BusinessActor_id-123.xml" -> []
     * 
     * @param xmlFilePath The XML file path
     * @return List of folder names (excluding "model" and the filename)
     */
    private List<String> parseFolderPath(String xmlFilePath) {
        List<String> folderPath = new ArrayList<>();
        
        // Normalize path separators
        String normalizedPath = xmlFilePath.replace('\\', '/');
        
        // Split by /
        String[] parts = normalizedPath.split("/");
        
        // Extract folder names (skip first if it's "model", skip last which is filename)
        int start = 0;
        int end = parts.length - 1; // Exclude filename
        
        if (parts.length > 0 && parts[0].equals("model")) {
            start = 1; // Skip "model" prefix
        }
        
        for (int i = start; i < end; i++) {
            if (!parts[i].isEmpty()) {
                folderPath.add(parts[i]);
            }
        }
        
        return folderPath;
    }
    
    /**
     * Find or create a folder by following a path of folder names.
     * 
     * @param model The model
     * @param folderPath List of folder names to navigate (e.g., ["business", "actors"])
     * @return The folder at the end of the path
     */
    private IFolder findOrCreateFolderByPath(IArchimateModel model, List<String> folderPath) {
        if (folderPath.isEmpty()) {
            return null;
        }
        
        // Start with the root folder for this type
        // The first folder name tells us which root folder to start from
        String rootFolderName = folderPath.get(0);
        IFolder currentFolder = findRootFolderByName(model, rootFolderName);
        
        if (currentFolder == null) {
            // Unknown root folder - return default
            return model.getDefaultFolderForObject(null);
        }
        
        // Navigate through subfolder path (starting from index 1)
        for (int i = 1; i < folderPath.size(); i++) {
            String folderName = folderPath.get(i);
            currentFolder = findOrCreateSubfolder(currentFolder, folderName);
        }
        
        return currentFolder;
    }
    
    /**
     * Find a root folder by name.
     * 
     * Grafico typically uses these root folder names:
     * - "business" -> Business folder
     * - "application" -> Application folder
     * - "technology" -> Technology folder
     * - "motivation" -> Motivation folder
     * - "implementation" -> Implementation & Migration folder
     * - "other" -> Other folder
     * - "relations" -> Relations folder
     * - "views" -> Views folder
     * 
     * @param model The model
     * @param folderName The folder name from the file path
     * @return The root folder, or null if not found
     */
    private IFolder findRootFolderByName(IArchimateModel model, String folderName) {
        // Map folder name to FolderType
        FolderType folderType = FolderType.getByName(folderName);
        
        if (folderType != null) {
            return model.getFolder(folderType);
        }
        
        // Fallback: search by name
        for (IFolder folder : model.getFolders()) {
            if (folder.getName().equalsIgnoreCase(folderName)) {
                return folder;
            }
        }
        
        return null;
    }
       
    /**
     * Find or create a subfolder within a parent folder.
     * 
     * @param parentFolder The parent folder
     * @param subfolderName The name of the subfolder to find/create
     * @return The subfolder
     */
    private IFolder findOrCreateSubfolder(IFolder parentFolder, String subfolderName) {
        // First, try to find existing subfolder
        for (IFolder subfolder : parentFolder.getFolders()) {
            if (subfolder.getName().equalsIgnoreCase(subfolderName)) {
                return subfolder;
            }
        }
        
        // Not found - create it
        IFolder newFolder = IArchimateFactory.eINSTANCE.createFolder();
        newFolder.setName(subfolderName);
        newFolder.setType(parentFolder.getType()); // Same type as parent
        parentFolder.getFolders().add(newFolder);
        
        System.out.println("Created subfolder: " + subfolderName + " in " + parentFolder.getName());
        
        return newFolder;
    }
    
    /**
     * Get the root folder for an element based on its type.
     * This is a fallback when we don't have a file path.
     * 
     * @param model The model
     * @param element The element
     * @return The appropriate root folder
     */
    private IFolder getRootFolderForElement(IArchimateModel model, EObject element) {
        // Use Archi's built-in logic to determine folder
        return model.getDefaultFolderForObject(element);
    }
    
    /**
     * Alternative implementation: Store file paths in a map during loading
     * for more accurate folder resolution.
     */
    public static class FolderFinderWithCache {
        
        // Map element ID -> original file path
        private Map<String, String> elementIdToFilePath = new HashMap<>();
        
        /**
         * Register an element's file path during loading.
         */
        public void registerElementPath(String elementId, String xmlFilePath) {
            elementIdToFilePath.put(elementId, xmlFilePath);
        }
        
        /**
         * Find folder for element using cached file path.
         */
        public IFolder findFolderForElement(IArchimateModel model, EObject element) {
            if (element instanceof IIdentifier) {
                String elementId = ((IIdentifier) element).getId();
                String xmlFilePath = elementIdToFilePath.get(elementId);
                
                if (xmlFilePath != null) {
                    FolderFinder finder = new FolderFinder();
                    return finder.findOrCreateFolderForElement(model, element, xmlFilePath);
                }
            }
            
            // Fallback
            return model.getDefaultFolderForObject(element);
        }
    }
}
