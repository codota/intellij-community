/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.annotate;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.annotate.*;
import com.intellij.openapi.vcs.history.VcsAbstractHistorySession;
import com.intellij.openapi.vcs.history.VcsFileRevision;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.openapi.vcs.vfs.VcsFileSystem;
import com.intellij.openapi.vcs.vfs.VcsVirtualFile;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcs.AnnotationProviderEx;
import com.intellij.vcsUtil.VcsUtil;
import git4idea.GitFileRevision;
import git4idea.GitRevisionNumber;
import git4idea.GitUtil;
import git4idea.commands.GitCommand;
import git4idea.commands.GitSimpleHandler;
import git4idea.history.GitHistoryUtils;
import git4idea.i18n.GitBundle;
import git4idea.util.StringScanner;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GitAnnotationProvider implements AnnotationProviderEx, VcsCacheableAnnotationProvider {
  private final Project myProject;
  @NonNls private static final String AUTHOR_KEY = "author";
  @NonNls private static final String COMMITTER_TIME_KEY = "committer-time";
  private static final Logger LOG = Logger.getInstance(GitAnnotationProvider.class);

  public GitAnnotationProvider(@NotNull Project project) {
    myProject = project;
  }

  public FileAnnotation annotate(@NotNull VirtualFile file) throws VcsException {
    return annotate(file, null);
  }

  public FileAnnotation annotate(@NotNull final VirtualFile file, final VcsFileRevision revision) throws VcsException {
    if (file.isDirectory()) {
      throw new VcsException("Cannot annotate a directory");
    }
    final FileAnnotation[] annotation = new FileAnnotation[1];
    final Exception[] exception = new Exception[1];
    Runnable command = new Runnable() {
      public void run() {
        try {
          final FilePath currentFilePath = VcsUtil.getFilePath(file.getPath());
          final FilePath realFilePath;
          setProgressIndicatorText(GitBundle.message("getting.history", file.getName()));
          final List<VcsFileRevision> revisions = GitHistoryUtils.history(myProject, currentFilePath);
          if (revision == null) {
            realFilePath = GitHistoryUtils.getLastCommitName(myProject, currentFilePath);
          }
          else {
            realFilePath = ((GitFileRevision)revision).getPath();
          }
          setProgressIndicatorText(GitBundle.message("computing.annotation", file.getName()));
          VcsRevisionNumber revisionNumber = revision != null ? revision.getRevisionNumber() : null;
          final GitFileAnnotation result = annotate(realFilePath, revisionNumber, revisions, file);
          annotation[0] = result;
        }
        catch (ProcessCanceledException pce) {
          throw pce;
        }
        catch (Exception e) {
          exception[0] = e;
        }
      }
    };
    if (ApplicationManager.getApplication().isDispatchThread()) {
      ProgressManager.getInstance().runProcessWithProgressSynchronously(command, GitBundle.getString("annotate.action.name"), false,
                                                                        myProject);
    }
    else {
      command.run();
    }
    if (exception[0] != null) {
      throw new VcsException("Failed to annotate: " + exception[0], exception[0]);
    }
    return annotation[0];
  }

  @NotNull
  @Override
  public FileAnnotation annotate(@NotNull final FilePath path, @NotNull final VcsRevisionNumber revision) throws VcsException {
    setProgressIndicatorText(GitBundle.message("getting.history", path.getName()));
    List<VcsFileRevision> revisions = GitHistoryUtils.history(myProject, path, null, revision);

    GitFileRevision fileRevision = new GitFileRevision(myProject, path, (GitRevisionNumber)revision);
    VcsVirtualFile file = new VcsVirtualFile(path.getPath(), fileRevision, VcsFileSystem.getInstance());

    setProgressIndicatorText(GitBundle.message("computing.annotation", path.getName()));
    return annotate(path, revision, revisions, file);
  }

  private static void setProgressIndicatorText(@Nullable String text) {
    final ProgressIndicator progress = ProgressManager.getInstance().getProgressIndicator();
    if (progress != null) progress.setText(text);
  }

  private GitFileAnnotation annotate(@NotNull final FilePath repositoryFilePath,
                                     @Nullable final VcsRevisionNumber revision,
                                     @NotNull final List<VcsFileRevision> revisions,
                                     @NotNull final VirtualFile file) throws VcsException {
    GitSimpleHandler h = new GitSimpleHandler(myProject, GitUtil.getGitRoot(repositoryFilePath), GitCommand.BLAME);
    h.setStdoutSuppressed(true);
    h.setCharset(file.getCharset());
    h.addParameters("--porcelain", "-l", "-t", "-w");
    if (revision == null) {
      h.addParameters("HEAD");
    }
    else {
      h.addParameters(revision.asString());
    }
    h.endOptions();
    h.addRelativePaths(repositoryFilePath);
    String output = h.run();
    GitFileAnnotation annotation = new GitFileAnnotation(myProject, file, revision == null ? null : revision);
    class CommitInfo {
      Date date;
      String author;
      GitRevisionNumber revision;
    }
    HashMap<String, CommitInfo> commits = new HashMap<String, CommitInfo>();
    for (StringScanner s = new StringScanner(output); s.hasMoreData();) {
      // parse header line
      String commitHash = s.spaceToken();
      if (commitHash.equals(GitRevisionNumber.NOT_COMMITTED_HASH)) {
        commitHash = null;
      }
      s.spaceToken(); // skip revision line number
      String s1 = s.spaceToken();
      int lineNum = Integer.parseInt(s1);
      s.nextLine();
      // parse commit information
      CommitInfo commit = commits.get(commitHash);
      if (commit != null) {
        while (s.hasMoreData() && !s.startsWith('\t')) {
          s.nextLine();
        }
      }
      else {
        commit = new CommitInfo();
        while (s.hasMoreData() && !s.startsWith('\t')) {
          String key = s.spaceToken();
          String value = s.line();
          if (commitHash != null && AUTHOR_KEY.equals(key)) {
            commit.author = value;
          }
          if (commitHash != null && COMMITTER_TIME_KEY.equals(key)) {
            commit.date = GitUtil.parseTimestampWithNFEReport(value, h, output);
            commit.revision = new GitRevisionNumber(commitHash, commit.date);
          }
        }
        commits.put(commitHash, commit);
      }
      // parse line
      if (!s.hasMoreData()) {
        // if the file is empty, the next line will not start with tab and it will be
        // empty.  
        continue;
      }
      s.skipChars(1);
      String line = s.line(true);
      annotation.appendLineInfo(commit.date, commit.revision, commit.author, line, lineNum);
    }
    annotation.addLogEntries(revisions);
    return annotation;
  }

  @Override
  public VcsAnnotation createCacheable(FileAnnotation fileAnnotation) {
    final GitFileAnnotation gitFileAnnotation = (GitFileAnnotation) fileAnnotation;
    final int size = gitFileAnnotation.getNumLines();
    final VcsUsualLineAnnotationData basicData = new VcsUsualLineAnnotationData(size);
    for (int i = 0; i < size; i++) {
      basicData.put(i,  gitFileAnnotation.getLineRevisionNumber(i));
    }
    return new VcsAnnotation(VcsUtil.getFilePath(gitFileAnnotation.getFile()), basicData, null);
  }

  @Override
  public FileAnnotation restore(VcsAnnotation vcsAnnotation,
                                VcsAbstractHistorySession session,
                                String annotatedContent,
                                boolean forCurrentRevision, VcsRevisionNumber revisionNumber) {
    final GitFileAnnotation gitFileAnnotation =
      new GitFileAnnotation(myProject, vcsAnnotation.getFilePath().getVirtualFile(), revisionNumber);
    gitFileAnnotation.addLogEntries(session.getRevisionList());
    final VcsLineAnnotationData basicAnnotation = vcsAnnotation.getBasicAnnotation();
    final int size = basicAnnotation.getNumLines();
    final Map<VcsRevisionNumber,VcsFileRevision> historyAsMap = session.getHistoryAsMap();
    final List<String> lines = StringUtil.split(StringUtil.convertLineSeparators(annotatedContent), "\n", false, false);
    for (int i = 0; i < size; i++) {
      final VcsRevisionNumber revision = basicAnnotation.getRevision(i);
      final VcsFileRevision vcsFileRevision = historyAsMap.get(revision);
      if (vcsFileRevision == null) {
        return null;
      }
      try {
        gitFileAnnotation.appendLineInfo(vcsFileRevision.getRevisionDate(), (GitRevisionNumber) revision, vcsFileRevision.getAuthor(),
                                         lines.get(i), i + 1);
      }
      catch (VcsException e) {
        return null;
      }
    }
    return gitFileAnnotation;
  }

  public boolean isAnnotationValid(VcsFileRevision rev) {
    return true;
  }
}
