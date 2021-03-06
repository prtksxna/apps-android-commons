package org.wikimedia.commons.media;

import android.app.DownloadManager;
import android.content.*;
import android.database.Cursor;
import android.net.*;
import android.os.*;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v4.view.ViewPager;
import android.util.Log;
import android.view.*;
import com.actionbarsherlock.app.SherlockFragment;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.actionbarsherlock.widget.ShareActionProvider;

import org.wikimedia.commons.*;
import org.wikimedia.commons.contributions.Contribution;
import org.wikimedia.commons.contributions.ContributionsActivity;

public class MediaDetailPagerFragment extends SherlockFragment implements ViewPager.OnPageChangeListener {
    private ViewPager pager;
    private Boolean editable;
    private CommonsApplication app;

    public void onPageScrolled(int i, float v, int i2) {
        getSherlockActivity().supportInvalidateOptionsMenu();
    }

    public void onPageSelected(int i) {
    }

    public void onPageScrollStateChanged(int i) {

    }

    public interface MediaDetailProvider {
        public Media getMediaAtPosition(int i);
        public int getTotalMediaCount();
        public void notifyDatasetChanged();
    }
    private class MediaDetailAdapter extends FragmentStatePagerAdapter {

        public MediaDetailAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public Fragment getItem(int i) {
            if(i == 0) {
                // See bug https://code.google.com/p/android/issues/detail?id=27526
                pager.postDelayed(new Runnable() {
                    public void run() {
                        getSherlockActivity().supportInvalidateOptionsMenu();
                    }
                }, 5);
            }
            return MediaDetailFragment.forMedia(i, editable);
        }

        @Override
        public int getCount() {
            return ((MediaDetailProvider)getActivity()).getTotalMediaCount();
        }
    }

    public MediaDetailPagerFragment() {
        this(false);
    }

    public MediaDetailPagerFragment(Boolean editable) {
        this.editable = editable;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_media_detail_pager, container, false);
        pager = (ViewPager) view.findViewById(R.id.mediaDetailsPager);
        pager.setOnPageChangeListener(this);
        pager.setAdapter(new MediaDetailAdapter(getChildFragmentManager()));
        if(savedInstanceState != null) {
            final int pageNumber = savedInstanceState.getInt("current-page");
            // Adapter doesn't seem to be loading immediately.
            // Dear God, please forgive us for our sins
            view.postDelayed(new Runnable() {
                public void run() {
                    pager.setCurrentItem(pageNumber, false);
                    getSherlockActivity().supportInvalidateOptionsMenu();
                }
            }, 100);
        }
        return view;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("current-page", pager.getCurrentItem());
        outState.putBoolean("editable", editable);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if(savedInstanceState != null) {
            editable = savedInstanceState.getBoolean("editable");
        }
        app = (CommonsApplication)getActivity().getApplicationContext();
        setHasOptionsMenu(true);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        MediaDetailProvider provider = (MediaDetailProvider)getSherlockActivity();
        Media m = provider.getMediaAtPosition(pager.getCurrentItem());
        switch(item.getItemId()) {
            case R.id.menu_share_current_image:
                EventLog.schema(CommonsApplication.EVENT_SHARE_ATTEMPT)
                        .param("username", app.getCurrentAccount().name)
                        .param("filename", m.getFilename())
                        .log();
                Intent shareIntent = new Intent();
                shareIntent.setAction(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TEXT, m.getDisplayTitle() + " " + m.getDescriptionUrl());
                startActivity(shareIntent);
                return true;
            case R.id.menu_browser_current_image:
                Intent viewIntent = new Intent();
                viewIntent.setAction(Intent.ACTION_VIEW);
                viewIntent.setData(Uri.parse(m.getDescriptionUrl()));
                startActivity(viewIntent);
                return true;
            case R.id.menu_download_current_image:
                downloadMedia(m);
                return true;
            case R.id.menu_retry_current_image:
                // Is this... sane? :)
                ((ContributionsActivity)getActivity()).retryUpload(pager.getCurrentItem());
                getSherlockActivity().getSupportFragmentManager().popBackStack();
                return true;
            case R.id.menu_abort_current_image:
                // todo: delete image
                ((ContributionsActivity)getActivity()).deleteUpload(pager.getCurrentItem());
                getSherlockActivity().getSupportFragmentManager().popBackStack();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Start the media file downloading to the local SD card/storage.
     * The file can then be opened in Gallery or other apps.
     *
     * @param m
     */
    private void downloadMedia(Media m) {
        String imageUrl = m.getImageUrl(),
               fileName = m.getFilename();
        // Strip 'File:' from beginning of filename, we really shouldn't store it
        fileName = fileName.replaceFirst("^File:", "");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            // Gingerbread DownloadManager has no HTTPS support...
            // Download file over HTTP, there'll be no credentials
            // sent so it should be safe-ish.
            imageUrl = imageUrl.replaceFirst("^https://", "http://");
        }
        Uri imageUri = Uri.parse(imageUrl);

        DownloadManager.Request req = new DownloadManager.Request(imageUri);
        req.setDescription(getString(R.string.app_name));
        req.setTitle(m.getDisplayTitle());
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            // Modern Android updates the gallery automatically. Yay!
            req.allowScanningByMediaScanner();

            // On HC/ICS/JB we can leave the download notification up when complete.
            // This allows folks to open the file directly in gallery viewer.
            // But for some reason it fails on Honeycomb (Google TV). Sigh.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            }
        }

        final DownloadManager manager = (DownloadManager)getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
        final long downloadId = manager.enqueue(req);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
            // For Gingerbread compatibility...
            BroadcastReceiver onComplete = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    // Check if the download has completed...
                    Cursor c = manager.query(new DownloadManager.Query()
                            .setFilterById(downloadId)
                            .setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL | DownloadManager.STATUS_FAILED)
                    );
                    if (c.moveToFirst()) {
                        int status = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                        Log.d("Commons", "Download completed with status " + status);
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            // Force Gallery to index the new file
                            Uri mediaUri = Uri.parse("file://" + Environment.getExternalStorageDirectory());
                            getActivity().sendBroadcast(new Intent(Intent.ACTION_MEDIA_MOUNTED, mediaUri));

                            // todo: show a persistent notification?
                        }
                    } else {
                        Log.d("Commons", "Couldn't get download status for some reason");
                    }
                    getActivity().unregisterReceiver(this);
                }
            };
            getActivity().registerReceiver(onComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        if(!editable) { // Disable menu options for editable views
            menu.clear(); // see http://stackoverflow.com/a/8495697/17865
            inflater.inflate(R.menu.fragment_image_detail, menu);
            if(pager != null) {
                MediaDetailProvider provider = (MediaDetailProvider)getSherlockActivity();
                Media m = provider.getMediaAtPosition(pager.getCurrentItem());
                if(m != null && !m.getFilename().startsWith("File:")) {
                    // Crude way of checking if the file has been successfully saved!
                    menu.findItem(R.id.menu_browser_current_image).setEnabled(false).setVisible(false);
                    menu.findItem(R.id.menu_share_current_image).setEnabled(false).setVisible(false);
                    menu.findItem(R.id.menu_download_current_image).setEnabled(false).setVisible(false);
                    menu.findItem(R.id.menu_retry_current_image).setEnabled(true).setVisible(true);
                    menu.findItem(R.id.menu_abort_current_image).setEnabled(true).setVisible(true);
                    return;
                }
            }
        }
    }

    public void showImage(int i) {
        pager.setCurrentItem(i);
    }
}