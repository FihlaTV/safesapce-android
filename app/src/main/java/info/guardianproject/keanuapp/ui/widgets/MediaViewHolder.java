package info.guardianproject.keanuapp.ui.widgets;

import android.net.Uri;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import info.guardianproject.keanuapp.R;

/**
 * Created by n8fr8 on 8/10/15.
 */
public class MediaViewHolder extends RecyclerView.ViewHolder  {

    public ImageView mMediaThumbnail;
    public ImageView mMediaPlay;

    public ViewGroup mContainer;

    // save the media uri while the MediaScanner is creating the thumbnail
    // if the holder was reused, the pair is broken
    public Uri mMediaUri = null;

    public MediaViewHolder (View view)
    {
        super(view);

        mMediaThumbnail = view.findViewById(R.id.media_thumbnail);
        mContainer = view.findViewById(R.id.message_container);
        mMediaPlay = view.findViewById(R.id.media_thumbnail_play);

    }
}

