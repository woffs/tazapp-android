package de.thecode.android.tazreader.start;


import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

import com.afollestad.materialdialogs.MaterialDialog;

import de.mateware.dialog.Dialog;
import de.mateware.dialog.DialogAdapterList;
import de.mateware.dialog.DialogIndeterminateProgress;
import de.mateware.dialog.LicenceDialog;
import de.mateware.dialog.licences.Agpl30Licence;
import de.mateware.dialog.licences.Apache20Licence;
import de.mateware.dialog.licences.BsdLicence;
import de.mateware.dialog.licences.MitLicence;
import de.mateware.dialog.listener.DialogAdapterListListener;
import de.mateware.dialog.listener.DialogButtonListener;
import de.mateware.dialog.listener.DialogCancelListener;
import de.mateware.dialog.listener.DialogDismissListener;
import de.mateware.snacky.Snacky;
import de.thecode.android.tazreader.BuildConfig;
import de.thecode.android.tazreader.R;
import de.thecode.android.tazreader.TazApplicationKt;
import de.thecode.android.tazreader.data.Download;
import de.thecode.android.tazreader.data.DownloadEvent;
import de.thecode.android.tazreader.data.DownloadState;
import de.thecode.android.tazreader.data.Paper;
import de.thecode.android.tazreader.data.PaperWithDownloadState;
import de.thecode.android.tazreader.data.Resource;
import de.thecode.android.tazreader.data.ResourceRepository;
import de.thecode.android.tazreader.data.ResourceWithDownloadState;
import de.thecode.android.tazreader.data.TazSettings;
import de.thecode.android.tazreader.dialog.ArchiveDialog;
import de.thecode.android.tazreader.dialog.ArchiveEntry;
import de.thecode.android.tazreader.dialog.HelpDialog;
import de.thecode.android.tazreader.dialognew.DownloadInfoDialog;
import de.thecode.android.tazreader.download.TazDownloadManager;
import de.thecode.android.tazreader.notifications.NotificationUtils;
import de.thecode.android.tazreader.reader.ReaderActivity;
import de.thecode.android.tazreader.start.library.LibraryFragment;
import de.thecode.android.tazreader.sync.AccountHelper;
import de.thecode.android.tazreader.sync.SyncErrorEvent;
import de.thecode.android.tazreader.utils.AsyncTaskListener;
import de.thecode.android.tazreader.utils.BaseActivity;
import de.thecode.android.tazreader.utils.Charsets;
import de.thecode.android.tazreader.utils.Connection;
import de.thecode.android.tazreader.utils.ConnectionInfo;
import de.thecode.android.tazreader.utils.StreamUtils;
import de.thecode.android.tazreader.utils.UserDeviceInfo;
import de.thecode.android.tazreader.widget.CustomToolbar;
import de.thecode.android.tazreader.worker.DataFolderMigrationWorker;
import de.thecode.android.tazreader.worker.SyncWorker;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProviders;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import timber.log.Timber;

/**
 * Created by mate on 27.01.2015.
 */
public class StartActivity extends BaseActivity
        implements DownloadInfoDialog.CancelDownloadDialogListener, DialogButtonListener, DialogDismissListener,
        DialogCancelListener, DialogAdapterListListener {

    //private static final String DIALOG_FIRST                 = "dialogFirst";
    private static final String DIALOG_USER_REENTER          = "dialogUserReenter";
    //private static final String DIALOG_MISSING_RESOURCE = "dialogMissingResource";
    private static final String DIALOG_NO_CONNECTION         = "dialogNoConnection";
    private static final String DIALOG_DOWNLOAD_MOBILE       = "dialogDownloadMobile";
    private static final String DIALOG_ACCOUNT_ERROR         = "dialogAccountError";
    private static final String DIALOG_DOWNLOADMANAGER_ERROR = "dialogDownloadManagerError";
    private static final String DIALOG_ARCHIVE_YEAR          = "dialogArchiveYear";
    private static final String DIALOG_ARCHIVE_MONTH         = "dialogArchiveMonth";
    private static final String DIALOG_WAIT                  = "dialogWait";
    private static final String DIALOG_LICENCES              = "dialogLicences";
    private static final String DIALOG_ERROR_OPEN_PAPER      = "dialogErrorOpenPaper";
    private static final String DIALOG_UPDATE_AVAILABLE      = "dialogUpdateAvailable";

    private static final String ARGUMENT_ARCHIVE_YEAR           = "archiveYear";

    private CustomToolbar            toolbar;
    private NavigationDrawerFragment mDrawerFragment;
    private View                     logWritingMessageView;

    NavigationDrawerFragment.NavigationItem userItem;
    NavigationDrawerFragment.NavigationItem libraryItem;
    NavigationDrawerFragment.NavigationItem preferencesItem;

    NavigationDrawerFragment.ClickItem      helpItem;
    NavigationDrawerFragment.NavigationItem imprintItem;
    NavigationDrawerFragment.ClickItem      privacyTermsItem;
    NavigationDrawerFragment.ClickItem      rateAppItem;
    NavigationDrawerFragment.ClickItem      reportErrorItem;

    private StartViewModel startViewModel;
    private MaterialDialog migrationDialog;

    TazSettings.OnPreferenceChangeListener<Boolean> demoModeChanged = this::onDemoModeChanged;

    TazSettings.OnPreferenceChangeListener<Boolean> logWritingListener = this::onLogWriting;

    @Override
    public void onStart() {
        super.onStart();
        startViewModel.getSettings()
                      .addOnPreferenceChangeListener(TazSettings.PREFKEY.DEMOMODE, demoModeChanged);
        startViewModel.getSettings()
                      .addOnPreferenceChangeListener(TazSettings.PREFKEY.LOGFILE, logWritingListener);
    }

    @Override
    public void onStop() {
        startViewModel.getSettings()
                      .removeOnPreferenceChangeListener(demoModeChanged);
        startViewModel.getSettings()
                      .removeOnPreferenceChangeListener(logWritingListener);
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        Timber.i("receiving new intent");
        //TODO handle intent data to open book
        super.onNewIntent(intent);
        openReaderFromDownloadNotificationIntent(intent);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        startViewModel = ViewModelProviders.of(this)
                                           .get(StartViewModel.class);

        setContentView(R.layout.activity_start);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setItemColor(ContextCompat.getColor(this, R.color.toolbar_foreground_color));
        toolbar.setTitleTextAppearance(this, R.style.Toolbar_TitleText);

        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        updateTitle();

        logWritingMessageView = findViewById(R.id.logWritingMessage);
        onLogWriting(TazSettings.getInstance(this)
                                .isWriteLogfile());

        mDrawerFragment = (NavigationDrawerFragment) getSupportFragmentManager().findFragmentById(R.id.fragment_navigation_drawer);
        mDrawerFragment.setUp(R.id.fragment_navigation_drawer, findViewById(R.id.drawer_layout), toolbar);

        userItem = new NavigationDrawerFragment.NavigationItem(getString(R.string.drawer_account),
                                                               R.drawable.ic_account,
                                                               LoginFragment.class);
        libraryItem = new NavigationDrawerFragment.NavigationItem(getString(R.string.drawer_library),
                                                                  R.drawable.ic_local_library_black_24dp,
                                                                  LibraryFragment.class);

        preferencesItem = new NavigationDrawerFragment.NavigationItem(getString(R.string.drawer_preferences),
                                                                      R.drawable.ic_settings_black_24dp,
                                                                      PreferencesFragment.class);
        preferencesItem.setAccessibilty(false);


        helpItem = new NavigationDrawerFragment.ClickItem(getString(R.string.drawer_help), R.drawable.ic_help_black_24dp);
        helpItem.setAccessibilty(false);
        privacyTermsItem = new NavigationDrawerFragment.ClickItem(getString(R.string.drawer_privacy_terms),
                                                                  R.drawable.ic_security_black_24dp);
        privacyTermsItem.setAccessibilty(false);
        imprintItem = new NavigationDrawerFragment.NavigationItem(getString(R.string.drawer_imprint),
                                                                  R.drawable.ic_info_variant_black_24dp,
                                                                  ImprintFragment.class);

        rateAppItem = new NavigationDrawerFragment.ClickItem(getString(R.string.drawer_rate_app), R.drawable.ic_star_black_24dp);
        rateAppItem.setAccessibilty(false);
        reportErrorItem = new NavigationDrawerFragment.NavigationItem(getString(R.string.drawer_report_error),
                                                                      R.drawable.ic_bug_report_black_24dp,
                                                                      ReportErrorFragment.class);
        reportErrorItem.setAccessibilty(false);

        mDrawerFragment.addItem(libraryItem);
        mDrawerFragment.addDividerItem();
        mDrawerFragment.addItem(userItem);
        mDrawerFragment.addItem(preferencesItem);
        mDrawerFragment.addItem(helpItem);
        mDrawerFragment.addDividerItem();
        mDrawerFragment.addItem(rateAppItem);
        mDrawerFragment.addItem(reportErrorItem);
        mDrawerFragment.addDividerItem();
        mDrawerFragment.addItem(imprintItem);
        mDrawerFragment.addItem(privacyTermsItem);

        //initial Fragment with colored navdrawer
        if (getSupportFragmentManager().findFragmentById(R.id.content_frame) == null)
            mDrawerFragment.simulateClick(libraryItem, false);


        if (TazSettings.getInstance(this)
                       .getPrefBoolean(TazSettings.PREFKEY.FISRTSTART, true)) {
            SyncWorker.scheduleJobImmediately(false);
            TazSettings.getInstance(this)
                       .setPref(TazSettings.PREFKEY.FISRTSTART, false);
            TazSettings.getInstance(this)
                       .setPref(TazSettings.PREFKEY.USERMIGRATIONNOTIFICATION, true);
        } else if (!TazSettings.getInstance(this)
                               .getPrefBoolean(TazSettings.PREFKEY.USERMIGRATIONNOTIFICATION, false)) {
            new Dialog.Builder().setCancelable(false)
                                .setMessage(R.string.dialog_user_reenter)
                                .setPositiveButton(R.string.dialog_understood)
                                .buildSupport()
                                .show(getSupportFragmentManager(), DIALOG_USER_REENTER);
        }

        //Todo run Sync on first start
        openReaderFromDownloadNotificationIntent(getIntent());
        startViewModel.getDownloadErrorLiveSingleData()
                      .observe(this, downloadError -> {
                              if (downloadError != null) {
                                  showDownloadErrorDialog(
                                          downloadError.getDownload().getTitle(),
                                          downloadError.getState().getText()
                                  );
                              }
                      });

        WorkManager.getInstance()
                   .getWorkInfosForUniqueWorkLiveData(DataFolderMigrationWorker.UNIQUE_TAG)
                   .observe(this, workStatuses -> {
                           boolean isDataMigrationRunning = false;
                           if (workStatuses != null) {
                               for (WorkInfo workStatus : workStatuses) {
                                   Timber.i("%s", workStatus);
                                   isDataMigrationRunning = workStatus.getState() == WorkInfo.State.RUNNING;
                                   if (isDataMigrationRunning) break;
                               }
                           }
                           if (isDataMigrationRunning) {
                               migrationDialog = DataFolderMigrationWorker.Companion.createWaitDialog(StartActivity.this);
                               migrationDialog.show();
                           } else {
                               if (migrationDialog != null) migrationDialog.dismiss();
                           }
                   });
    }

    private void openReaderFromDownloadNotificationIntent(Intent intent) {
        if (intent != null) {
            if (intent.hasExtra(NotificationUtils.NOTIFICATION_EXTRA_TYPE_ID) && intent.hasExtra(NotificationUtils.NOTIFICATION_EXTRA_BOOKID)) {
                String bookId = intent.getStringExtra(NotificationUtils.NOTIFICATION_EXTRA_BOOKID);
                int type = intent.getIntExtra(NotificationUtils.NOTIFICATION_EXTRA_TYPE_ID, -1);
                if ((type == NotificationUtils.DOWNLOAD_NOTIFICATION_ID || type == NotificationUtils.AUDIOSERVICE_NOTIFICATION_ID) && !TextUtils.isEmpty(
                        bookId)) {
                    openReader(bookId);
                }
            }
        }
    }

    public void loadFragment(NavigationDrawerFragment.NavigationItem item) {
        if (item.getTarget() != null) {
            //noinspection TryWithIdenticalCatches
            try {
                Fragment fragment = item.getTarget()
                                        .newInstance();
                FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
                ft.replace(R.id.content_frame, fragment);
                //                if (firstFragmentLoaded) ft.addToBackStack(null);
                //                else firstFragmentLoaded = true;
                //ft.addToBackStack(item.getTarget().getSimpleName());
                ft.commit();
                startViewModel.addToNavBackstack(item);
            } catch (InstantiationException e) {
                Timber.e(e);
            } catch (IllegalAccessException e) {
                Timber.e(e);
            }
        }
    }

    public void onNavigationClick(NavigationDrawerFragment.ClickItem item) {
        Timber.i("");
        if (helpItem.equals(item)) {
            showHelpDialog(HelpDialog.HELP_LIBRARY);
        } else if (privacyTermsItem.equals(item)) {
            showHelpDialog(HelpDialog.HELP_PRIVACY);
        } else if (rateAppItem.equals(item)) {
            Intent rateIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + BuildConfig.APPLICATION_ID));
            rateIntent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);

            if (rateIntent.resolveActivity(getPackageManager()) != null) {
                startActivity(rateIntent);
            } else {
                rateIntent = new Intent(Intent.ACTION_VIEW,
                                        Uri.parse("http://play.google.com/store/apps/details?id=" + BuildConfig.APPLICATION_ID));
                if (rateIntent.resolveActivity(getPackageManager()) != null) {
                    startActivity(rateIntent);
                } else {
                    Toast.makeText(this, "No play store or browser app", Toast.LENGTH_LONG)
                         .show();
                }
            }
        }

    }

    @Override
    public void onBackPressed() {
        if (startViewModel.getNavBackstack()
                          .size() > 1) {
            startViewModel.getNavBackstack()
                          .remove(startViewModel.getNavBackstack()
                                                .size() - 1);
            NavigationDrawerFragment.NavigationItem backstackItem = startViewModel.getNavBackstack()
                                                                                  .get(startViewModel.getNavBackstack()
                                                                                                     .size() - 1);
            loadFragment(backstackItem);
        } else {
            super.onBackPressed();
        }
    }


    public void onSuccessfulCredentialsCheck() {
        mDrawerFragment.simulateClick(libraryItem, false);
    }

    public void onDemoModeChanged(boolean demoMode) {
        updateTitle();
    }

    public void onLogWriting(boolean writeToLog) {
        logWritingMessageView.setVisibility(writeToLog ? View.VISIBLE : View.GONE);
    }


    public Toolbar getToolbar() {
        return toolbar;
    }

    public void enableDrawer(boolean bool) {
        mDrawerFragment.setEnabled(bool);
    }


    public void onUpdateDrawer(Fragment fragment) {
        mDrawerFragment.setActive(fragment.getClass());
    }


    public void startDownload(Paper downloadPaper) {
        ConnectionInfo info = Connection.Companion.getConnectionInfo();
        if (!info.getConnected()) {
            showNoConnectionDialog();
        } else if (info.getMetered()) {
            addToDownloadQueue(downloadPaper.getBookId());
            if (startViewModel.isMobileDownloadAllowed()) startViewModel.startDownloadQueue();
            else showMobileConnectionDialog();
        } else {
            addToDownloadQueue(downloadPaper.getBookId());
            startViewModel.startDownloadQueue();
        }
    }

    private void addToDownloadQueue(String bookId) {
        startViewModel.getDownloadQueue()
                      .add(bookId);
        startViewModel.setOpenPaperIdAfterDownload(bookId);
    }

    public void showNoConnectionDialog() {
        new Dialog.Builder().setMessage(R.string.dialog_noconnection)
                            .setPositiveButton()
                            .buildSupport()
                            .show(getSupportFragmentManager(), DIALOG_NO_CONNECTION);
    }

    private void showMobileConnectionDialog() {

        new Dialog.Builder().setMessage(R.string.dialog_mobile_download)
                            .setPositiveButton(R.string.yes)
                            .setNegativeButton()
                            .setNeutralButton(R.string.dialog_wifi)
                            .buildSupport()
                            .show(getSupportFragmentManager(), DIALOG_DOWNLOAD_MOBILE);
    }

    private void showLicencesDialog() {
        new LicenceDialog.Builder().addEntry(new Apache20Licence(this,
                                                                 "Android Support Library",
                                                                 "The Android Open Source Project",
                                                                 2011))
                                   .addEntry(new Apache20Licence(this, "OkHttp", "Square, Inc.", 2016))
                                   .addEntry(new Apache20Licence(this, "Picasso", "Square, Inc.", 2013))
                                   .addEntry(new Apache20Licence(this, "Picasso 2 OkHttp 3 Downloader", "Jake Wharton", 2016))
                                   .addEntry(new Apache20Licence(this, "AESCrypt-Android", "Scott Alexander-Bown", 2014))
                                   .addEntry(new Apache20Licence(this, "Snacky", "Mate Siede", 2017))
                                   .addEntry(new Apache20Licence(this, "DataFragment", "Mate Siede", 2017))
                                   .addEntry(new MitLicence(this, "dd-plist", "Daniel Dreibrodt", 2016))
                                   .addEntry(new Apache20Licence(this, "cwac-provider", "Mark Murphy", 2016))
                                   .addEntry(new Apache20Licence(this, "Centering Recycler View", "Shigehiro Soejima", 2015))
                                   .addEntry(new Apache20Licence(this,
                                                                 "EventBus 3",
                                                                 "Markus Junginger, greenrobot (http://greenrobot.org)",
                                                                 2016))
                                   .addEntry(new Apache20Licence(this, "Commons IO", "The Apache Software Foundation", 2016))
                                   .addEntry(new Apache20Licence(this, "RecyclerView-FlexibleDivider", "yqritc", 2016))
                                   .addEntry(new Agpl30Licence(this, "mupdf", "Artifex Software, Inc.", 2015))
                                   .addEntry(new BsdLicence(this, "Stetho", "Facebook, Inc.", 2015))
                                   .setPositiveButton()
                                   .buildSupport()
                                   .show(getSupportFragmentManager(), DIALOG_LICENCES);
    }

    private void showErrorDialog(String message, String tag) {
        new Dialog.Builder().setMessage(message)
                            .setPositiveButton()
                            .setCancelable(false)
                            .buildSupport()
                            .show(getSupportFragmentManager(), tag);
    }

    private void showArchiveYearPicker() {
        Calendar cal = Calendar.getInstance();
        ArrayList<ArchiveEntry> years = new ArrayList<>();
        for (int year = cal.get(Calendar.YEAR); year >= 2011; year--) {
            years.add(new ArchiveEntry(year));
        }


        new ArchiveDialog.Builder().setEntries(years)
                                   .setTitle(R.string.dialog_archive_year_title)
                                   .setCancelable(true)
                                   .buildSupport()
                                   .show(getSupportFragmentManager(), DIALOG_ARCHIVE_YEAR);
    }

    private void showArchiveMonthPicker(int year) {
        Timber.d("year: %s", year);
        Calendar cal = Calendar.getInstance();
        int currentYear = cal.get(Calendar.YEAR);
        int maxMonth = 11;
        if (year == currentYear) {
            maxMonth = cal.get(Calendar.MONTH);
        }
        ArrayList<ArchiveEntry> months = new ArrayList<>();
        for (int i = maxMonth; i >= 0; i--) {
            months.add(new ArchiveEntry(i, new DateFormatSymbols().getMonths()[i]));
        }

        Bundle bundle = new Bundle();
        bundle.putInt(ARGUMENT_ARCHIVE_YEAR, year);

        new ArchiveDialog.Builder().setEntries(months)
                                   .setTitle(R.string.dialog_archive_month_title)
                                   .setCancelable(true)
                                   .addBundle(bundle)
                                   .buildSupport()
                                   .show(getSupportFragmentManager(), DIALOG_ARCHIVE_MONTH);
    }

    private void showDownloadErrorDialog(String downloadTitle, String extraMessage) {
        StringBuilder message = new StringBuilder(String.format(getString(R.string.dialog_error_download), downloadTitle));
        if (!TextUtils.isEmpty(extraMessage)) message.append("\n\n")
                                                     .append(extraMessage);

        new Dialog.Builder().setMessage(message.toString())
                            .setPositiveButton()
                            .buildSupport()
                            .show(getSupportFragmentManager(), DIALOG_DOWNLOADMANAGER_ERROR);
    }

    public void showWaitDialog(String tag, String message) {
        DialogFragment dialog = (DialogFragment) getSupportFragmentManager().findFragmentByTag(tag);
        if (dialog == null) new DialogIndeterminateProgress.Builder().setCancelable(false)
                                                                     .setMessage(message)
                                                                     .buildSupport()
                                                                     .show(getSupportFragmentManager(), tag);
    }

    public void hideWaitDialog(String tag) {
        DialogFragment dialog = (DialogFragment) getSupportFragmentManager().findFragmentByTag(tag);
        if (dialog != null) dialog.dismiss();
    }

    public void callArchive() {
        showArchiveYearPicker();
    }

    public void openReader(String bookId) {
        new AsyncTaskListener<String, PaperWithDownloadState>(bookIdParams -> startViewModel.getPaperRepository().getWithBookId(bookIdParams[0]),
                                                              paper -> {
                                                                  if (paper.getDownloadState() != DownloadState.READY) {
                                                                      showErrorDialog(getString(R.string.message_paper_not_downloaded),
                                                                                      DIALOG_ERROR_OPEN_PAPER);
                                                                  } else {
                                                                      if (!startViewModel.getStorageManager()
                                                                                         .getPaperDirectory(paper)
                                                                                         .exists()) {
                                                                          showErrorDialog(getString(R.string.message_paper_files_not_downloaded),
                                                                                          DIALOG_ERROR_OPEN_PAPER);
                                                                      } else {
                                                                          new AsyncTaskListener<Paper, ResourceWithDownloadState>(
                                                                                  papers -> ResourceRepository.getInstance(
                                                                                          StartActivity.this)
                                                                                                              .getResourceForPaper(
                                                                                                                      papers[0]),
                                                                                  resource -> {
                                                                                      if (resource.getDownloadState() == DownloadState.READY && startViewModel.getStorageManager()
                                                                                                                                                              .getResourceDirectory(
                                                                                                                                                                      resource)
                                                                                                                                                              .exists()) {
                                                                                          Timber.i("start reader for paper: %s",
                                                                                                   paper);
                                                                                          Intent intent = new Intent(StartActivity.this,
                                                                                                                     ReaderActivity.class);
                                                                                          intent.putExtra(ReaderActivity.KEY_EXTRA_BOOK_ID,
                                                                                                          paper.getBookId());
                                                                                          startActivity(intent);
                                                                                      } else {
                                                                                          ConnectionInfo info = Connection.Companion.getConnectionInfo();
                                                                                          if (!info.getConnected()) {
                                                                                              Timber.e(new ConnectException(
                                                                                                      "Keine Verbindung"));
                                                                                              showErrorDialog(getString(R.string.message_resource_not_downloaded_no_connection),
                                                                                                              DIALOG_ERROR_OPEN_PAPER);
                                                                                          } else {
                                                                                              new AsyncTaskListener<Resource, TazDownloadManager.Result>(
                                                                                                      resources -> {
                                                                                                          return TazDownloadManager.Companion.getInstance()
                                                                                                                                             .downloadResource(
                                                                                                                                                     resources[0].getKey(),
                                                                                                                                                     false,
                                                                                                                                                     true);
                                                                                                      },
                                                                                                      result -> {
                                                                                                          if (result.getState() != TazDownloadManager.Result.STATE.SUCCESS) {
                                                                                                              showDownloadErrorDialog(
                                                                                                                      getString(R.string.message_resourcedownload_error),
                                                                                                                      result.getState()
                                                                                                                            .getText());
                                                                                                          } else {
                                                                                                              startViewModel.setOpenPaperIdAfterDownload(
                                                                                                                      paper.getBookId());
                                                                                                              startViewModel.resourceKeyWaitingForDownload = result.getDownload()
                                                                                                                                                                   .getKey();
                                                                                                              Timber.e(new Resource.MissingResourceException());
                                                                                                              showWaitDialog(
                                                                                                                      DIALOG_WAIT + paper.getBookId(),
                                                                                                                      getString(R.string.dialog_meassage_loading_missing_resource));

                                                                                                          }
                                                                                                      }).execute(resource);
                                                                                          }
                                                                                      }
                                                                                  }).execute(paper);
                                                                      }
                                                                  }
                                                              }).execute(bookId);

    }

    public void updateTitle() {
        StringBuilder titleBuilder = new StringBuilder(getString(getApplicationInfo().labelRes));
        if (TazSettings.getInstance(this)
                       .isDemoMode()) {
            titleBuilder.append(" ")
                        .append(getString(R.string.demo_titel_appendix));
        }
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(titleBuilder.toString());
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onSyncError(SyncErrorEvent event) {
        Snacky.builder()
              .setView(findViewById(R.id.content_frame))
              .setDuration(Snacky.LENGTH_LONG)
              .setText(getString(R.string.sync_job_error, event.getMessage()))
              .setActionText(android.R.string.ok)
              .error()
              .show();
    }


    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDownloadEvent(DownloadEvent event) {
        Timber.d("event: %s", event);
        Download download = event.getDownload();
        switch (download.getType()) {
            case PAPER:
                if (event.getSuccess()) {
                    if (startViewModel.isOpenReaderAfterDownload()) {
                        if (download.getKey()
                                    .equals(startViewModel.getOpenPaperIdAfterDownload())) {
                            startViewModel.removeOpenPaperIdAfterDownload();
                            openReader(download.getKey());
                        }
                    }
                } else {
                    showDownloadErrorDialog(download.getTitle(), event.getMessage());
                    NotificationUtils.getInstance(this)
                                     .removeDownloadNotification(download.getKey());
                }
                break;
            case RESOURCE:
                if (event.getDownload()
                         .getKey()
                         .equals(startViewModel.resourceKeyWaitingForDownload)) {
                    startViewModel.resourceKeyWaitingForDownload = null;
                    String bookId = startViewModel.getOpenPaperIdAfterDownload();
                    if (bookId != null) {
                        startViewModel.removeOpenPaperIdAfterDownload();
                        hideWaitDialog(DIALOG_WAIT + bookId);
                        if (event.getSuccess()) {
                            openReader(bookId);
                        } else {
                            showDownloadErrorDialog(getString(R.string.message_resourcedownload_error),
                                                    String.format(getString(R.string.message_resourcedownload_late_error),
                                                                  event.getMessage()));
                        }
                    }
                }

                break;
        }
    }

    AsyncTask<Void, Void, Boolean> firstStartTask;

    @Override
    protected void onResume() {
        super.onResume();
        new AsyncTaskListener<Void, Boolean>(voids -> {
            boolean showDialog = false;
            List<Paper> allPapers = startViewModel.getPaperRepository()
                                                  .getAllPapers();
            boolean foundDownloaded = false;
            for (Paper paper : allPapers) {
                if (foundDownloaded) break;
                foundDownloaded = startViewModel.getPaperRepository()
                                                .getDownloadStateForPaper(paper.getBookId()) != DownloadState.NONE;
            }
            if (!foundDownloaded) {
                showDialog = startViewModel.getSettings()
                                           .isDemoMode();
            }
            return showDialog;
        }, aBoolean -> {
            if (aBoolean) showHelpDialog(HelpDialog.HELP_INTRO);
        }).execute();
    }

    @Override
    protected void onPause() {
        if (firstStartTask != null) firstStartTask.cancel(true);
        super.onPause();
    }

    @Override
    public void onDialogClick(String tag, Bundle arguments, int which) {
        super.onDialogClick(tag, arguments, which);
        if (DIALOG_HELP.equals(tag)) {
            if (which == Dialog.BUTTON_NEUTRAL) mDrawerFragment.simulateClick(userItem, true);
        } else if (DIALOG_USER_REENTER.equals(tag)) {
            if (which == Dialog.BUTTON_POSITIVE) {
                TazSettings.getInstance(this)
                           .setPref(TazSettings.PREFKEY.USERMIGRATIONNOTIFICATION, true);
                mDrawerFragment.simulateClick(userItem, true);
            }
        } else if (ImprintFragment.DIALOG_TECHINFO.equals(tag)) {
            switch (which) {
                case Dialog.BUTTON_NEUTRAL:
                    showLicencesDialog();
                    break;
            }
        } else if (DIALOG_DOWNLOAD_MOBILE.equals(tag)) {
            switch (which) {
                case Dialog.BUTTON_POSITIVE:
                    startViewModel.setMobileDownloadAllowed(true);
                    startViewModel.startDownloadQueue();
                    break;
                case Dialog.BUTTON_NEGATIVE:
                    startViewModel.getDownloadQueue()
                                  .clear();
                    break;
                case Dialog.BUTTON_NEUTRAL:
                    startViewModel.getDownloadQueue()
                                  .clear();
                    startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS));
                    break;
            }
        } else if (DIALOG_ACCOUNT_ERROR.equals(tag)) {
            finish();
        } else if (ImprintFragment.DIALOG_ERRORMAIL.equals(tag)) {
            switch (which) {
                case Dialog.BUTTON_POSITIVE:
                    try {
                        try (InputStream bodyInputStream = getAssets().open("errorReportMail/body.txt")) {

                            String aboId = AccountHelper.getInstance(this)
                                                        .getUser("");
                            UserDeviceInfo userDeviceInfo = UserDeviceInfo.getInstance(this);

                            String body = StreamUtils.toString(bodyInputStream, Charsets.UTF_8);
                            body = body.replaceFirst("\\{appversion\\}", userDeviceInfo.getVersionName());
                            body = body.replaceFirst("\\{installid\\}", userDeviceInfo.getInstallationId());
                            body = body.replaceFirst("\\{aboid\\}", aboId);
                            body = body.replaceFirst("\\{androidVersion\\}",
                                                     Build.VERSION.SDK_INT + " (" + Build.VERSION.RELEASE + ")");
                            body = body.replaceFirst("\\{pushToken\\}",
                                                     TazSettings.getInstance(this)
                                                                .getFirebaseToken());

                            Intent emailIntent = new Intent(Intent.ACTION_SEND);
                            emailIntent.setType("message/rfc822");
                            emailIntent.putExtra(Intent.EXTRA_EMAIL, new String[]{BuildConfig.ERRORMAIL});
                            emailIntent.putExtra(Intent.EXTRA_SUBJECT,
                                                 getString(R.string.errormail_subject, getString(R.string.app_name), aboId));
                            emailIntent.putExtra(Intent.EXTRA_TEXT, body);
                            startActivity(emailIntent);
                        }
                    } catch (IOException | ActivityNotFoundException e) {
                        Timber.e(e);
                    }
                    break;
                case Dialog.BUTTON_NEUTRAL:
                    mDrawerFragment.simulateClick(preferencesItem, true);
                    break;
            }
        }
    }


    @Override
    public void onDialogAdapterListClick(String tag, DialogAdapterList.DialogAdapterListEntry entry, Bundle arguments) {
        super.onDialogAdapterListClick(tag, entry, arguments);
        if (DIALOG_ARCHIVE_YEAR.equals(tag)) {
            if (getResources().getBoolean(R.bool.archive_monthly)) {
                showArchiveMonthPicker(((ArchiveEntry) entry).getNumber());
            } else {
                int year = ((ArchiveEntry) entry).getNumber();
                Calendar startCal = Calendar.getInstance();
                Calendar endCal = Calendar.getInstance();
                startCal.set(year, Calendar.JANUARY, 1);
                endCal.set(year, Calendar.DECEMBER, 31);
                SyncWorker.scheduleJobImmediately(true, startCal, endCal);
            }
        } else if (DIALOG_ARCHIVE_MONTH.equals(tag)) {
            int year = arguments.getInt(ARGUMENT_ARCHIVE_YEAR);
            int month = ((ArchiveEntry) entry).getNumber();
            Calendar startCal = Calendar.getInstance();
            Calendar endCal = Calendar.getInstance();
            startCal.set(year, month, 1);
            int lastDayOfMont = startCal.getActualMaximum(Calendar.DAY_OF_MONTH);
            endCal.set(year, month, lastDayOfMont);
            SyncWorker.scheduleJobImmediately(true, startCal, endCal);
        }
    }


    @Override
    public void onDialogDismiss(String tag, Bundle arguments) {
        super.onDialogDismiss(tag, arguments);
        if (DIALOG_DOWNLOAD_MOBILE.equals(tag)) {
            startViewModel.getDownloadQueue()
                          .clear();
        }
    }

    @Override
    public void onDialogCancel(String tag, Bundle arguments) {
        super.onDialogCancel(tag, arguments);
        if (DIALOG_DOWNLOAD_MOBILE.equals(tag)) {
            startViewModel.getDownloadQueue()
                          .clear();
        }
    }

    @Override
    public void onCancelDownload(@NotNull String bookId) {
        startViewModel.deletePaper(bookId);
    }
}
