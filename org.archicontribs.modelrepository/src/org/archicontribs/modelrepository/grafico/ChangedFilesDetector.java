package org.archicontribs.modelrepository.grafico;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;

/**
 * Utility class to detect which files have changed between two Git commits.
 * This is used to optimize selective EMF reloading after a git pull/fetch.
 * 
 * Usage:
 * <pre>
 * ChangedFilesDetector detector = new ChangedFilesDetector(gitFolder);
 * String beforeCommit = detector.getCurrentCommitHash();
 * // ... perform git pull ...
 * String afterCommit = detector.getCurrentCommitHash();
 * Set<String> changedFiles = detector.getChangedXmlFilesBetweenCommits(beforeCommit, afterCommit);
 * detector.close();
 * </pre>
 * 
 * @author Johnny Huynh
 */
public class ChangedFilesDetector {
    
    private final Repository repository;
    private final Git git;
    
    /**
     * Constructor
     * @param localGitFolder The .git folder or working directory
     * @throws IOException if the repository cannot be opened
     */
    public ChangedFilesDetector(File localGitFolder) throws IOException {
        this.git = Git.open(localGitFolder);
        this.repository = git.getRepository();
    }
    
    /**
     * Get the current HEAD commit hash
     * @return The commit hash as a string, or null if HEAD cannot be resolved
     * @throws IOException if unable to resolve HEAD
     */
    public String getCurrentCommitHash() throws IOException {
        ObjectId head = repository.resolve("HEAD");
        return head != null ? head.getName() : null;
    }
    
    /**
     * Get list of XML files that changed between two commits.
     * This is the primary method for selective reload - it filters to only .xml files.
     * 
     * @param oldCommitHash The old/before commit hash
     * @param newCommitHash The new/after commit hash
     * @return Set of XML file paths (relative to repository root) that were added, modified, or deleted
     * @throws IOException if there's an error accessing the repository
     * @throws GitAPIException if there's an error with Git operations
     */
    public Set<String> getChangedXmlFilesBetweenCommits(String oldCommitHash, String newCommitHash) 
            throws IOException, GitAPIException {
        
        Set<String> allChangedFiles = getChangedFilesBetweenCommits(oldCommitHash, newCommitHash);
        Set<String> xmlFiles = new HashSet<>();
        
        for (String filePath : allChangedFiles) {
            if (filePath.endsWith(".xml")) {
                xmlFiles.add(filePath);
            }
        }
        
        return xmlFiles;
    }
    
    /**
     * Get list of all files (not just XML) that changed between two commits.
     * 
     * @param oldCommitHash The old/before commit hash
     * @param newCommitHash The new/after commit hash
     * @return Set of file paths (relative to repository root) that were added, modified, or deleted
     * @throws IOException if there's an error accessing the repository
     * @throws GitAPIException if there's an error with Git operations
     */
    public Set<String> getChangedFilesBetweenCommits(String oldCommitHash, String newCommitHash) 
            throws IOException, GitAPIException {
        
        Set<String> changedFiles = new HashSet<>();
        
        try (ObjectReader reader = repository.newObjectReader();
             DiffFormatter diffFormatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            
            diffFormatter.setRepository(repository);
            
            // Get tree iterators for both commits
            AbstractTreeIterator oldTreeIter = prepareTreeParser(oldCommitHash);
            AbstractTreeIterator newTreeIter = prepareTreeParser(newCommitHash);
            
            // Get the diff entries
            List<DiffEntry> diffs = diffFormatter.scan(oldTreeIter, newTreeIter);
            
            // Extract file paths from diff entries
            for (DiffEntry diff : diffs) {
                switch (diff.getChangeType()) {
                    case ADD:
                        // File was added
                        changedFiles.add(diff.getNewPath());
                        break;
                    case MODIFY:
                        // File was modified
                        changedFiles.add(diff.getNewPath());
                        break;
                    case DELETE:
                        // File was deleted
                        changedFiles.add(diff.getOldPath());
                        break;
                    case RENAME:
                        // File was renamed - both old and new paths are relevant
                        changedFiles.add(diff.getOldPath());
                        changedFiles.add(diff.getNewPath());
                        break;
                    case COPY:
                        // File was copied
                        changedFiles.add(diff.getNewPath());
                        break;
                }
            }
        }
        
        return changedFiles;
    }
    
    /**
     * Prepare a tree parser for a given commit
     * @param commitHash The commit hash
     * @return Tree iterator for the commit
     * @throws IOException if unable to prepare tree
     */
    private AbstractTreeIterator prepareTreeParser(String commitHash) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(repository.resolve(commitHash));
            RevTree tree = walk.parseTree(commit.getTree().getId());
            
            CanonicalTreeParser treeParser = new CanonicalTreeParser();
            try (ObjectReader reader = repository.newObjectReader()) {
                treeParser.reset(reader, tree.getId());
            }
            
            walk.dispose();
            return treeParser;
        }
    }
    
    /**
     * Close the Git repository.
     * Should be called when done using this detector.
     */
    public void close() {
        if (git != null) {
            git.close();
        }
    }
}
