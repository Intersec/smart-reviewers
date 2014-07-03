// Copyright (C) 2013 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.smartreviewers;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.BlameCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.ChangeMessage;
import com.google.gerrit.reviewdb.client.Account.Id;
import com.google.gerrit.reviewdb.client.Patch.ChangeType;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.PatchSetApproval;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountByEmailCache;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.change.ChangesCollection;
import com.google.gerrit.server.change.PostReviewers;
import com.google.gerrit.server.patch.PatchList;
import com.google.gerrit.server.patch.PatchListCache;
import com.google.gerrit.server.patch.PatchListEntry;
import com.google.gerrit.server.patch.PatchListNotAvailableException;
import com.google.gerrit.server.project.ChangeControl;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gwtorm.jdbc.JdbcSchema;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.assistedinject.Assisted;


public class SmartReviewers implements Runnable {

  private static final Logger log = LoggerFactory
      .getLogger(SmartReviewers.class);

  private final RevCommit commit;
  private final Change change;
  private final PatchSet ps;
  private final Repository repo;
  private final int maxReviewers;
  private final int weightBlame;
  private final int weightLastReviews;
  private final int weightWorkload;
  private final ReviewDb reviewDb;

  private final AccountByEmailCache byEmailCache;
  private final AccountCache accountCache;
  private final PatchListCache patchListCache;
  private final Provider<PostReviewers> reviewersProvider;
  private final IdentifiedUser.GenericFactory identifiedUserFactory;
  private final ChangeControl.GenericFactory changeControlFactory;

  public interface Factory {
    SmartReviewers create(RevCommit commit, Change change, PatchSet ps,
        int maxReviewers, int weightBlame, int weightLastReviews, int weightWorkload, Repository repo, ReviewDb reviewDb);
  }

  @Inject
  public SmartReviewers(final AccountByEmailCache byEmailCache,
      final AccountCache accountCache,
      final ChangeControl.GenericFactory changeControlFactory,
      final ChangesCollection changes,
      final Provider<PostReviewers> reviewersProvider,
      final IdentifiedUser.GenericFactory identifiedUserFactory,
      final PatchListCache patchListCache, final ProjectCache projectCache,
      @Assisted final RevCommit commit, @Assisted final Change change,
      @Assisted final PatchSet ps, @Assisted final int maxReviewers,
      @Assisted final int weightBlame, @Assisted final int weightLastReviews,
      @Assisted final int weightWorkload, @Assisted final Repository repo,
      @Assisted final ReviewDb reviewDb) {
    this.byEmailCache = byEmailCache;
    this.accountCache = accountCache;
    this.changeControlFactory = changeControlFactory;
    this.reviewersProvider = reviewersProvider;
    this.identifiedUserFactory = identifiedUserFactory;
    this.patchListCache = patchListCache;
    this.commit = commit;
    this.change = change;
    this.ps = ps;
    this.maxReviewers = maxReviewers;
    this.weightBlame = weightBlame;
    this.weightLastReviews = weightLastReviews;
    this.weightWorkload = weightWorkload;
    this.repo = repo;
    this.reviewDb = reviewDb;
  }

  @Override
  public void run() {
    Map<Account, Integer> reviewers = Maps.newHashMap();
    PatchList patchList;

    try {
      patchList = patchListCache.get(change, ps);
    } catch (PatchListNotAvailableException ex) {
      log.error("Couldn't load patchlist for change {}", change.getKey(), ex);
      return;
    }

    // Ignore merges and initial commit.
    if (commit.getParentCount() != 1) {
      return;
    }

    // For each file impacted by the commit...
    for (PatchListEntry entry : patchList.getPatches()) {
      if (entry.getChangeType() != ChangeType.MODIFIED &&
          entry.getChangeType() != ChangeType.DELETED)
      {
        continue;
      }

      // Get reviewers by blame
      BlameResult blameResult = computeBlame(entry, commit.getParent(0));
      if (blameResult != null) {
          List<Edit> edits = entry.getEdits();

        getReviewersFromBlame(edits, blameResult, reviewers);
      }

      // Get reviewer by last reviews
      getReviewersFromLastReviews(entry, reviewers);
    }

    // Take into account the number of incoming reviews per reviewer
    Iterator<Entry<Account, Integer>> it = reviewers.entrySet().iterator();
    while (it.hasNext()) {
      Account account = it.next().getKey();
      addAccount(account, reviewers, getNbIncomingReviews(account) * weightWorkload);
    }
    Set<Account.Id> topReviewers = findTopReviewers(reviewers);
    addReviewers(topReviewers, change);
    reviewDb.close();
  }

  /**
   * Append the reviewers to change#{@link Change}
   *
   * @param topReviewers Set of reviewers proposed
   * @param change {@link Change} to add the reviewers to
   */
  private void addReviewers(Set<Account.Id> topReviewers, Change change) {
    try {
      ChangeControl changeControl =
          changeControlFactory.controlFor(change,
              identifiedUserFactory.create(change.getOwner()));
      ChangeResource changeResource = new ChangeResource(changeControl);
      PostReviewers post = reviewersProvider.get();
      for (Account.Id accountId : topReviewers) {
        PostReviewers.Input input = new PostReviewers.Input();
        input.reviewer = accountId.toString();
        post.apply(changeResource, input);
      }
    } catch (Exception ex) {
      log.error("Couldn't add reviewers to the change", ex);
    }
  }


  /**
   * Create a set of reviewers based on data collected from line annotations,
   * the reviewers are ordered by their weight and n greatest of the entries
   * are chosen, where n is the maximum number of reviewers
   *
   * @param reviewers A set of reviewers with their weight mapped to their
   *        {@link Account}
   * @return Reviewers that are best matches for this change, empty if none,
   *         never <code>null</code>
   */
  private Set<Account.Id> findTopReviewers(final Map<Account, Integer> reviewers) {
    Set<Account.Id> topReviewers = Sets.newHashSet();
    List<Entry<Account, Integer>> entries =
        Ordering.from(new Comparator<Entry<Account, Integer>>() {
          public int compare(Entry<Account, Integer> first,
              Entry<Account, Integer> second) {
            return first.getValue() - second.getValue();
          }
        }).greatestOf(reviewers.entrySet(), this.maxReviewers);

    for (Entry<Account, Integer> entry : entries) {
      topReviewers.add(entry.getKey().getId());
    }
    return topReviewers;
  }

  /**
   * Fill a map of all the possible reviewers based on the provided blame data
   *
   * @param edits List of edits that were made for this patch
   * @param blameResult Result of blame computation
   * @param reviewers the reviewer hash table to fill
   */
  private void getReviewersFromBlame(final List<Edit> edits,
      final BlameResult blameResult, Map<Account, Integer> reviewers) {
    for (Edit edit : edits) {
      for (int i = edit.getBeginA(); i < edit.getEndA(); i++) {
        RevCommit commit = blameResult.getSourceCommit(i);
        Set<Account.Id> ids =
            byEmailCache.get(commit.getAuthorIdent().getEmailAddress());

        for (Account.Id id : ids) {
          Account account = accountCache.get(id).getAccount();
          addAccount(account, reviewers, weightBlame);
        }
      }
    }
  }

  /**
   * Compute the blame data for the parent, we are not interested in the
   * specific commit but the parent, since we only want to know the last person
   * that edited this specific part of the code.
   *
   * @param entry {@link PatchListEntry}
   * @param commit Parent {@link RevCommit}
   * @return Result of blame computation, null if the computation fails
   */
  private BlameResult computeBlame(final PatchListEntry entry,
      final RevCommit parent) {
    BlameCommand blameCommand = new BlameCommand(repo);
    blameCommand.setStartCommit(parent);
    blameCommand.setFilePath(entry.getNewName());
    try {
      BlameResult blameResult = blameCommand.call();
      blameResult.computeAll();
      return blameResult;
    } catch (GitAPIException ex) {
      log.error("Couldn't execute blame for commit {}", parent.getName(), ex);
    } catch (IOException err) {
      log.error("Error while computing blame for commit {}", parent.getName(),
          err);
    }
    return null;
  }

  private void addAccount(Account account, Map<Account, Integer> reviewers, Integer weight) {
    if (account.isActive() && !change.getOwner().equals(account.getId())) {
      Integer count = reviewers.get(account);
      reviewers.put(account, count == null ? weight : count.intValue() + weight);
    }
  }

  private int getNbIncomingReviews(Account account) {
    int result = 0;
    Statement stmt;
    try {
      stmt = ((JdbcSchema) reviewDb).getConnection().createStatement();
      ResultSet rs = stmt.executeQuery("SELECT c.change_id FROM patch_set_approvals" +
      		                           " AS psa INNER JOIN changes AS c ON c.change_id = psa.change_id AND c.open = 'Y'" +
      		                           " WHERE psa.account_id = " + account.getId().toString() + " GROUP BY c.change_id");
      while (rs.next()) {
        result++;
      }
      rs.close();
      stmt.close();
    } catch (SQLException e1) {
      log.error("getNbIncomingReviews failed because of its SQL Query.");
      e1.printStackTrace();
    }
    return result;
  }

  /**
   * Fill a map of all the possible reviewers based on the last reviews they made
   * on the modified file.
   *
   * @param entry {@link PatchListEntry}
   * @param reviewers the reviewer hash table to fill
   */
  void getReviewersFromLastReviews(final PatchListEntry entry,
      Map<Account, Integer> reviewers) {
    Git git = new Git(repo);
    Iterable<RevCommit> commits;

    // Get the last 10 commits impacting the file
    try {
      commits = git.log().addPath(entry.getNewName()).setMaxCount(10).call();
    } catch (Exception ex) {
      log.error("Couldn't execute log for file {}", entry.getNewName(), ex);
      return;
    }

    // For each commit...
    for (RevCommit commit : commits) {
      List<String> change_ids = commit.getFooterLines("Change-Id");

      if (change_ids.size() != 1) {
        continue;
      }

      // Get the related changes
      List<Change> changes;
      try {
        Change.Key key = new Change.Key(change_ids.get(0));
        changes = reviewDb.changes().byKey(key).toList();
      } catch (Exception ex) {
        log.error("Couldn't get changes related to change-id {}", change_ids.get(0), ex);
        continue;
      }

      // For each related change, add accounts who reviewed the file before
      for (Change change : changes) {
        try {
          Set<Id> authors = new HashSet<Id>();
          List<ChangeMessage> messages = reviewDb.changeMessages().byChange(change.getId()).toList();
          for (ChangeMessage message : messages) {
            authors.add(message.getAuthor());
          }

          List<PatchSetApproval> psas = reviewDb.patchSetApprovals().byChange(change.getId()).toList();
          for (PatchSetApproval psa : psas) {
            if (psa.getValue() == 0) {
              continue;
            }
            Account account = reviewDb.accounts().get(psa.getAccountId());
            if (!authors.isEmpty() && !authors.contains(psa.getAccountId())) {
              continue;
            }
            if (!psa.getLabel().contains("Code-Review")) {
              continue;
            }
            addAccount(account, reviewers, weightLastReviews);
          }
        } catch (OrmException e) {
          log.error("getReviewersFromLastReviews() failed");
          e.printStackTrace();
        }
      }
    }
  }

}
