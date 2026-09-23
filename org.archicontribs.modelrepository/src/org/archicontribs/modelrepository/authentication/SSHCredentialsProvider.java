/**
 * This program and the accompanying materials are made available under the
 * terms of the License which accompanies this distribution in the file
 * LICENSE.txt
 */
package org.archicontribs.modelrepository.authentication;

import java.io.IOException;
import java.security.GeneralSecurityException;

import org.eclipse.jgit.errors.UnsupportedCredentialItem;
import org.eclipse.jgit.transport.CredentialItem;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.URIish;

/**
 * Our extended CredentialsProvider
 * 
 * @author Phillip Beauvoir
 */
public class SSHCredentialsProvider extends CredentialsProvider {

    @Override
    public boolean isInteractive() {
        return false;
    }

    @Override
    public boolean supports(CredentialItem... items) {
        for(CredentialItem item : items) {
            if(item instanceof CredentialItem.YesNoType) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean get(URIish uri, CredentialItem... items) throws UnsupportedCredentialItem {
        for(CredentialItem item : items) {
            // For verifying and storing the key in the known_hosts file
            if(item instanceof CredentialItem.YesNoType) {
                throw new UnsupportedCredentialItem(uri, "SSH host-key verification requires explicit confirmation."); //$NON-NLS-1$
            }
            // Password for ssh file
            else if(item instanceof CredentialItem.Password) {
                try {
                    ((CredentialItem.Password)item).setValue(CredentialsAuthenticator.getSSHIdentityProvider().getIdentityPassword());
                }
                catch(IOException | GeneralSecurityException ex) {
                    ex.printStackTrace();
                    throw new UnsupportedCredentialItem(uri, "Could not get identity password."); //$NON-NLS-1$
                }
            }
        }

        return true;
    }
}
