/**
 * This program and the accompanying materials
 * are made available under the terms of the License
 * which accompanies this distribution in the file LICENSE.txt
 */
package org.archicontribs.modelrepository.actions;

import org.archicontribs.modelrepository.grafico.IArchiRepository;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.ui.handlers.HandlerUtil;

import com.archimatetool.model.util.Logger;


/**
 * Refresh model handler
 * 
 * @author Phillip Beauvoir
 */
public class RefreshModelHandler extends AbstractModelHandler {
    
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
    	// Manifest DEBUG
        long timeStart = System.currentTimeMillis();
        
        IArchiRepository repository = getActiveArchiRepository();
        
        if(repository != null) {
            RefreshModelAction action = new RefreshModelAction(HandlerUtil.getActiveWorkbenchWindowChecked(event));
            action.setRepository(repository);
            action.run();
        }
        
    	// Manifest DEBUG
        long timeEnd = System.currentTimeMillis();
        Logger.logInfo("Total Refresh Time: " + (timeEnd-timeStart) + "ms");
        
        return null;
    }
    
    @Override
    public boolean isEnabled() {
        return getActiveArchiRepository() != null;
    }
}
