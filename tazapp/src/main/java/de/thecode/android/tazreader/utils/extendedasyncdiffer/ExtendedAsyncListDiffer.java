package de.thecode.android.tazreader.utils.extendedasyncdiffer;

/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.AdapterListUpdateCallback;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListUpdateCallback;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.List;

/**
 * Helper for computing the difference between two lists via {@link DiffUtil} on a background
 * thread.
 * <p>
 * It can be connected to a
 * {@link RecyclerView.Adapter RecyclerView.Adapter}, and will signal the
 * adapter of changes between sumbitted lists.
 * <p>
 * For simplicity, the {@link ListAdapter} wrapper class can often be used instead of the
 * AsyncListDiffer directly. This AsyncListDiffer can be used for complex cases, where overriding an
 * adapter base class to support asynchronous List diffing isn't convenient.
 * <p>
 * The AsyncListDiffer can consume the values from a LiveData of <code>List</code> and present the
 * data simply for an adapter. It computes differences in list contents via {@link DiffUtil} on a
 * background thread as new <code>List</code>s are received.
 * <p>
 * Use {@link #getCurrentList()} to access the current List, and present its data objects. Diff
 * results will be dispatched to the ListUpdateCallback immediately before the current list is
 * updated. If you're dispatching list updates directly to an Adapter, this means the Adapter can
 * safely access list items and total size via {@link #getCurrentList()}.
 * <p>
 * A complete usage pattern with Room would look like this:
 * <pre>
 * {@literal @}Dao
 * interface UserDao {
 *     {@literal @}Query("SELECT * FROM user ORDER BY lastName ASC")
 *     public abstract LiveData&lt;List&lt;User>> usersByLastName();
 * }
 *
 * class MyViewModel extends ViewModel {
 *     public final LiveData&lt;List&lt;User>> usersList;
 *     public MyViewModel(UserDao userDao) {
 *         usersList = userDao.usersByLastName();
 *     }
 * }
 *
 * class MyActivity extends AppCompatActivity {
 *     {@literal @}Override
 *     public void onCreate(Bundle savedState) {
 *         super.onCreate(savedState);
 *         MyViewModel viewModel = ViewModelProviders.of(this).get(MyViewModel.class);
 *         RecyclerView recyclerView = findViewById(R.id.user_list);
 *         UserAdapter&lt;User> adapter = new UserAdapter();
 *         viewModel.usersList.observe(this, list -> adapter.submitList(list));
 *         recyclerView.setAdapter(adapter);
 *     }
 * }
 *
 * class UserAdapter extends RecyclerView.Adapter&lt;UserViewHolder> {
 *     private final AsyncListDiffer&lt;User> mDiffer = new AsyncListDiffer(this, DIFF_CALLBACK);
 *     {@literal @}Override
 *     public int getItemCount() {
 *         return mDiffer.getCurrentList().size();
 *     }
 *     public void submitList(List&lt;User> list) {
 *         mDiffer.submitList(list);
 *     }
 *     {@literal @}Override
 *     public void onBindViewHolder(UserViewHolder holder, int position) {
 *         User user = mDiffer.getCurrentList().get(position);
 *         holder.bindTo(user);
 *     }
 *     public static final DiffUtil.ItemCallback&lt;User> DIFF_CALLBACK
 *             = new DiffUtil.ItemCallback&lt;User>() {
 *         {@literal @}Override
 *         public boolean areItemsTheSame(
 *                 {@literal @}NonNull User oldUser, {@literal @}NonNull User newUser) {
 *             // User properties may have changed if reloaded from the DB, but ID is fixed
 *             return oldUser.getId() == newUser.getId();
 *         }
 *         {@literal @}Override
 *         public boolean areContentsTheSame(
 *                 {@literal @}NonNull User oldUser, {@literal @}NonNull User newUser) {
 *             // NOTE: if you use equals, your object must properly override Object#equals()
 *             // Incorrectly returning false here will result in too many animations.
 *             return oldUser.equals(newUser);
 *         }
 *     }
 * }</pre>
 *
 * @param <T> Type of the lists this AsyncListDiffer will receive.
 *
 * @see DiffUtil
 * @see AdapterListUpdateCallback
 */
public class ExtendedAsyncListDiffer<T> {
    private final ExtendedAdapterListUpdateCallback mUpdateCallback;
    private final ExtendedAsyncDifferConfig<T>      mConfig;

    /**
     * Convenience for
     * {@code AsyncListDiffer(new AdapterListUpdateCallback(adapter),
     * new AsyncDifferConfig.Builder().setDiffCallback(diffCallback).build());}
     *
     * @param adapter Adapter to dispatch position updates to.
     * @param diffCallback ItemCallback that compares items to dispatch appropriate animations when
     *
     * @see DiffUtil.DiffResult#dispatchUpdatesTo(RecyclerView.Adapter)
     */
    public ExtendedAsyncListDiffer(@NonNull RecyclerView.Adapter adapter,
                                   @NonNull DiffUtil.ItemCallback<T> diffCallback) {
        mUpdateCallback = new ExtendedAdapterListUpdateCallback(adapter);
        mConfig = new ExtendedAsyncDifferConfig.Builder<>(diffCallback).build();
    }

    /**
     * Create a AsyncListDiffer with the provided config, and ListUpdateCallback to dispatch
     * updates to.
     *
     * @param listUpdateCallback Callback to dispatch updates to.
     * @param config Config to define background work Executor, and DiffUtil.ItemCallback for
     *               computing List diffs.
     *
     * @see DiffUtil.DiffResult#dispatchUpdatesTo(RecyclerView.Adapter)
     */
    public ExtendedAsyncListDiffer(@NonNull ExtendedAdapterListUpdateCallback listUpdateCallback,
                                   @NonNull ExtendedAsyncDifferConfig<T> config) {
        mUpdateCallback = listUpdateCallback;
        mConfig = config;
    }

    @Nullable
    private List<T> mList;

    /**
     * Non-null, unmodifiable version of mList.
     * <p>
     * Collections.emptyList when mList is null, wrapped by Collections.unmodifiableList otherwise
     */
    @NonNull
    private List<T> mReadOnlyList = Collections.emptyList();

    // Max generation of currently scheduled runnable
    private int mMaxScheduledGeneration;

    /**
     * Get the current List - any diffing to present this list has already been computed and
     * dispatched via the ListUpdateCallback.
     * <p>
     * If a <code>null</code> List, or no List has been submitted, an empty list will be returned.
     * <p>
     * The returned list may not be mutated - mutations to content must be done through
     * {@link #submitList(List)}.
     *
     * @return current List.
     */
    @NonNull
    public List<T> getCurrentList() {
        return mReadOnlyList;
    }

    /**
     * Pass a new List to the AdapterHelper. Adapter updates will be computed on a background
     * thread.
     * <p>
     * If a List is already present, a diff will be computed asynchronously on a background thread.
     * When the diff is computed, it will be applied (dispatched to the {@link ListUpdateCallback}),
     * and the new List will be swapped in.
     *
     * @param newList The new List.
     */
    public void submitList(final List<T> newList) {
        if (newList == mList) {
            // nothing to do
            return;
        }

        // incrementing generation means any currently-running diffs are discarded when they finish
        final int runGeneration = ++mMaxScheduledGeneration;

        if (newList == null) {
            mUpdateCallback.onRemoved(0, mList.size());
            mList = null;
            mReadOnlyList = Collections.emptyList();
            return;
        }

        if (mList == null) {
            // fast simple first insert
            mUpdateCallback.onInserted(0, newList.size());
            mList = newList;
            mReadOnlyList = Collections.unmodifiableList(newList);
            return;
        }

        final List<T> oldList = mList;
        mConfig.getBackgroundThreadExecutor().execute(() -> {
            final DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                                          return oldList.size();
                                                                }

                @Override
                public int getNewListSize() {
                                          return newList.size();
                                                                }

                @Override
                public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                    return mConfig.getDiffCallback().areItemsTheSame(
                            oldList.get(oldItemPosition), newList.get(newItemPosition));
                }

                @Override
                public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                    return mConfig.getDiffCallback().areContentsTheSame(
                            oldList.get(oldItemPosition), newList.get(newItemPosition));
                }

                @Nullable
                @Override
                public Object getChangePayload(int oldItemPosition, int newItemPosition) {
                    return mConfig.getDiffCallback().getChangePayload(oldList.get(oldItemPosition), newList.get(newItemPosition));
                }
            });

            mConfig.getMainThreadExecutor().execute(() -> {
                if (mMaxScheduledGeneration == runGeneration) {
                    latchList(newList, result);
                }
            });
        });
    }

    private void latchList(@NonNull List<T> newList, @NonNull DiffUtil.DiffResult diffResult) {
        mList = newList;
        mReadOnlyList = Collections.unmodifiableList(newList);
        mUpdateCallback.resetCounters();
        diffResult.dispatchUpdatesTo(mUpdateCallback);
        mUpdateCallback.callListener();
    }


}
