/*******************************************************************************
 * Copyright (C) 2005-2014 Alfresco Software Limited.
 *
 * This file is part of Alfresco Mobile for Android.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.alfresco.mobile.android.application.editors.text;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

import org.alfresco.mobile.android.api.utils.IOUtils;
import org.alfresco.mobile.android.application.R;
import org.alfresco.mobile.android.application.activity.BaseActivity;
import org.alfresco.mobile.android.application.capture.DeviceCapture;
import org.alfresco.mobile.android.application.intent.AlfrescoIntentAPI;
import org.alfresco.mobile.android.application.intent.PublicIntentAPIUtils;
import org.alfresco.mobile.android.application.intent.RequestCode;
import org.alfresco.mobile.android.application.managers.ActionUtils;
import org.alfresco.mobile.android.async.Operator;
import org.alfresco.mobile.android.async.file.open.OpenFileEvent;
import org.alfresco.mobile.android.async.file.open.OpenFileRequest;
import org.alfresco.mobile.android.platform.AlfrescoNotificationManager;
import org.alfresco.mobile.android.platform.intent.PrivateIntent;
import org.alfresco.mobile.android.ui.utils.UIUtils;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.ScrollView;
import android.widget.TextView;

import com.squareup.otto.Subscribe;

public class TextEditorActivity extends BaseActivity
{
    private static final String TAG = TextEditorActivity.class.getName();

    private static final String ARGUMENT_TEXT = "TextEditorActivityText";

    private static final String ARGUMENT_CHARSET = "TextCharSet";

    private static final String ARGUMENT_TEXT_SIZE = "TextEditorActivityTextSize";

    private File file;

    private TextView tview;

    private boolean changed = false, modified = false;

    private String title;

    private int textSize = TextSizeDialogFragment.DEFAULT_TEXT_SIZE;

    private String defaultCharset = "UTF-8";

    private boolean hasTextToSpeech = false;

    private boolean isCreation = false, isSpeechToText = false;

    private int originalLength;

    private long ouputAccountId;

    private boolean hasOuput;

    private String outputFolderId;

    // ///////////////////////////////////////////////////////////////////////////
    // LIFECYCLE
    // ///////////////////////////////////////////////////////////////////////////
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.app_text_editor);

        setProgressBarIndeterminateVisibility(true);

        if (savedInstanceState != null)
        {
            textSize = savedInstanceState.getInt(ARGUMENT_TEXT_SIZE);
            defaultCharset = savedInstanceState.getString(ARGUMENT_CHARSET);
        }

        hasTextToSpeech = ActionUtils.hasSpeechToText(this);

        String action = getIntent().getAction();

        // CREATE Management
        // Use this intent if you want to create a text note directly inside the
        // text editor
        if (PrivateIntent.ACTION_CREATE_TEXT.equals(action))
        {
            isCreation = true;
            isSpeechToText = getIntent().getBooleanExtra(AlfrescoIntentAPI.EXTRA_SPEECH2TEXT, false);
            file = (File) getIntent().getSerializableExtra(PrivateIntent.EXTRA_FILE);
            ouputAccountId = getIntent().getLongExtra(AlfrescoIntentAPI.EXTRA_ACCOUNT_ID, -1);
            outputFolderId = getIntent().getStringExtra(AlfrescoIntentAPI.EXTRA_FOLDER_ID);
            hasOuput = (ouputAccountId != -1 && outputFolderId != null);

            Operator.with(this).load(new OpenFileRequest.Builder(file, defaultCharset));
            setTextShown(false);
            retrieveTitle();

            return;
        }

        if (Intent.ACTION_VIEW.equals(action))
        {
            if (getIntent().getData() != null)
            {
                String filePath = ActionUtils.getPath(this, getIntent().getData());
                file = new File(filePath);
                if (file.exists() && file.length() < getResources().getInteger(R.integer.text_editor_max_file_size))
                {

                    if (savedInstanceState != null)
                    {
                        displayText(savedInstanceState.getString(ARGUMENT_TEXT));
                        setProgressBarIndeterminateVisibility(false);
                    }
                    else
                    {
                        Operator.with(this).load(new OpenFileRequest.Builder(file, defaultCharset));
                        setTextShown(false);
                    }
                    retrieveTitle();
                }
                else if (file.exists()
                        && file.length() > getResources().getInteger(R.integer.text_editor_max_file_size))
                {
                    AlfrescoNotificationManager.getInstance(this).showLongToast(
                            getString(R.string.file_editor_error_too_large));
                    finish();
                }
                else
                {
                    AlfrescoNotificationManager.getInstance(this).showLongToast(
                            getString(R.string.file_editor_error_open));
                    finish();
                }
            }
            else
            {
                AlfrescoNotificationManager.getInstance(this).showLongToast(getString(R.string.file_editor_error_open));
                finish();
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState)
    {
        super.onSaveInstanceState(outState);

        tview = (TextView) findViewById(R.id.texteditor);
        outState.putString(ARGUMENT_TEXT, tview.getText().toString());
        outState.putString(ARGUMENT_CHARSET, defaultCharset);
        outState.putInt(ARGUMENT_TEXT_SIZE, textSize);
    }

    @Override
    protected void onStart()
    {
        getActionBar().setDisplayHomeAsUpEnabled(true);

        retrieveTitle();
        getActionBar().show();
        UIUtils.displayTitle(this, title);

        super.onStart();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode)
        {
            case RequestCode.TEXT_TO_SPEECH:
            {
                if (resultCode == RESULT_OK && data != null)
                {
                    ArrayList<String> text = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                    int start = tview.getSelectionStart();
                    int end = tview.getSelectionEnd();
                    ((Editable) tview.getText()).replace(Math.min(start, end), Math.max(start, end), text.get(0), 0,
                            text.get(0).length());
                }
                break;
            }
            default:
                break;
        }
    }

    // ///////////////////////////////////////////////////////////////////////////
    // MENU
    // ///////////////////////////////////////////////////////////////////////////
    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        super.onCreateOptionsMenu(menu);

        getActionBar().setDisplayShowTitleEnabled(true);
        MenuItem mi = menu.add(Menu.NONE, R.id.menu_editor_save, Menu.FIRST, R.string.save);
        mi.setIcon(R.drawable.ic_save);
        mi.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_WITH_TEXT);

        if (hasTextToSpeech)
        {
            mi = menu.add(Menu.NONE, R.id.menu_editor_speech, Menu.FIRST + 1, R.string.file_editor_speech_to_text);
            mi.setIcon(R.drawable.ic_microphone);
            mi.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        }

        mi = menu.add(Menu.NONE, R.id.menu_editor_encoding, Menu.FIRST + 2, R.string.file_editor_encoding);
        mi.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);

        mi = menu.add(Menu.NONE, R.id.menu_editor_font, Menu.FIRST + 3, R.string.file_editor_text_size);
        mi.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        switch (item.getItemId())
        {
            case R.id.menu_editor_save:
                save(false);
                return true;
            case R.id.menu_editor_encoding:
                displayEncoding();
                return true;
            case R.id.menu_editor_font:
                displayFontSize();
                return true;
            case R.id.menu_editor_speech:
                speechToText();
                return true;
            case android.R.id.home:
                if (file != null)
                {
                    if (hasChanged())
                    {
                        // Request to save before quit
                        requestSave();
                    }
                    else
                    {
                        finish();
                    }
                }
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    // ///////////////////////////////////////////////////////////////////////////
    // NAVIGATION
    // ///////////////////////////////////////////////////////////////////////////
    @Override
    public void onBackPressed()
    {
        if (file != null)
        {
            if (hasChanged())
            {
                // Request to save before quit
                requestSave();
            }
            else
            {
                if (modified && isCreation)
                {
                    exit();
                }
                else if (isCreation)
                {
                    file.delete();
                }
                super.onBackPressed();
            }
        }
    }

    // ///////////////////////////////////////////////////////////////////////////
    // UTILS
    // ///////////////////////////////////////////////////////////////////////////
    private void retrieveTitle()
    {
        if (file == null) { return; }
        title = file.getName();
    }

    private void setTextShown(Boolean shown)
    {
        if (shown)
        {
            findViewById(R.id.texteditor).setVisibility(View.VISIBLE);
            findViewById(R.id.empty).setVisibility(View.GONE);
        }
        else
        {
            findViewById(R.id.texteditor).setVisibility(View.GONE);
            findViewById(R.id.empty).setVisibility(View.VISIBLE);
        }
    }

    private void displayText(String text)
    {
        tview = (TextView) findViewById(R.id.texteditor);
        setTextSize(textSize);
        tview.setText(text);
        tview.addTextChangedListener(new TextWatcher()
        {
            public void afterTextChanged(Editable s)
            {
                if (!changed && s.length() != originalLength)
                {
                    changed = true;
                    modified = true;
                }
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after)
            {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count)
            {
            }
        });

        ScrollView scroll = (ScrollView) findViewById(R.id.scroll);
        scroll.setSmoothScrollingEnabled(true);

        setTextShown(true);
    }

    private void displayFontSize()
    {
        TextSizeDialogFragment.newInstance(textSize).show(getFragmentManager(), TextSizeDialogFragment.TAG);
    }

    private void displayEncoding()
    {
        EncodingDialogFragment.newInstance(defaultCharset).show(getFragmentManager(), EncodingDialogFragment.TAG);
    }

    private boolean hasChanged()
    {
        return changed;
    }

    private void speechToText()
    {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, Locale.getDefault());

        try
        {
            startActivityForResult(intent, RequestCode.TEXT_TO_SPEECH);
        }
        catch (ActivityNotFoundException a)
        {
            AlfrescoNotificationManager.getInstance(getApplicationContext()).showToast(
                    R.string.file_editor_error_speech);
        }
    }

    // ///////////////////////////////////////////////////////////////////////////
    // PUBLIC ACTION
    // ///////////////////////////////////////////////////////////////////////////
    private void exit()
    {
        if (isCreation)
        {
            if (hasOuput)
            {
                TextEditorActivity.this.startActivity(PublicIntentAPIUtils.uploadFileIntent(file, ouputAccountId,
                        outputFolderId));
                TextEditorActivity.this.finish();
            }
            else
            {
                ActionUtils.actionSendDocumentToAlfresco(TextEditorActivity.this, file);
            }
        }
    }

    public void save(boolean stopActivity)
    {
        TextView view = (TextView) findViewById(R.id.texteditor);
        OutputStream sourceFile = null;
        try
        {
            sourceFile = new FileOutputStream(file);
            sourceFile.write(view.getText().toString().getBytes("UTF-8"));
            sourceFile.close();

            changed = false;

            AlfrescoNotificationManager.getInstance(this).showToast(R.string.file_editor_save_confirmation);

            if (stopActivity)
            {
                this.finish();
            }
        }
        catch (Exception e)
        {
            Log.w(TAG, Log.getStackTraceString(e));
        }
        finally
        {
            IOUtils.closeStream(sourceFile);
        }
    }

    public void reload(String charset)
    {
        defaultCharset = charset;
        Operator.with(this).load(new OpenFileRequest.Builder(file, defaultCharset));
        setTextShown(false);
    }

    public void setTextSize(int size)
    {
        textSize = size;
        tview.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize);
    }

    public void requestSave()
    {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.file_editor_save_request);
        builder.setMessage(Html.fromHtml(getString(R.string.file_editor_save_description)));
        builder.setIcon(R.drawable.ic_save);
        builder.setPositiveButton(R.string.file_editor_save, new DialogInterface.OnClickListener()
        {
            public void onClick(DialogInterface dialog, int item)
            {
                save(true);
                exit();
                dialog.dismiss();
            }
        });
        builder.setNegativeButton(R.string.file_editor_discard, new DialogInterface.OnClickListener()
        {
            public void onClick(DialogInterface dialog, int item)
            {
                if (isCreation)
                {
                    file.delete();
                }
                dialog.dismiss();
                finish();
            }
        });
        builder.setNeutralButton(R.string.cancel, new DialogInterface.OnClickListener()
        {
            public void onClick(DialogInterface dialog, int item)
            {
                if (isCreation)
                {
                    file.delete();
                }
                dialog.dismiss();
            }
        });
        AlertDialog alert = builder.create();
        alert.show();
        TextView messageText = (TextView) alert.findViewById(android.R.id.message);
        messageText.setGravity(Gravity.CENTER);
    }

    protected String createFilename(String extension)
    {
        String timeStamp = new SimpleDateFormat(DeviceCapture.TIMESTAMP_PATTERN).format(new Date());

        return "note_" + timeStamp + "." + extension;
    }

    // ///////////////////////////////////////////////////////////////////////////
    // EVENT RECEIVERS
    // ///////////////////////////////////////////////////////////////////////////
    @Subscribe
    public void onOpenFile(OpenFileEvent event)
    {
        if (event.hasException)
        {
            AlfrescoNotificationManager.getInstance(this).showLongToast(getString(R.string.textfile_populate_failed));
        }
        else
        {
            if (event.data != null)
            {
                originalLength = event.data.length();
                displayText(event.data);
            }
        }
        // Display progress
        setProgressBarIndeterminateVisibility(false);

        if (isCreation)
        {
            if (isSpeechToText)
            {
                speechToText();
            }
            else
            {
                tview.requestFocus();
            }
        }
    }

}
