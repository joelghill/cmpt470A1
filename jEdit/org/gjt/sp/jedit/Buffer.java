/*
 * Buffer.java - jEdit buffer
 * :tabSize=8:indentSize=8:noTabs=false:
 * :folding=explicit:collapseFolds=1:
 *
 * Copyright (C) 1998, 2005 Slava Pestov
 * Portions copyright (C) 1999, 2000 mike dillon
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.gjt.sp.jedit;

//{{{ Imports
import javax.swing.*;
import javax.swing.text.*;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.*;
import org.gjt.sp.jedit.browser.VFSBrowser;
import org.gjt.sp.jedit.buffer.*;
import org.gjt.sp.jedit.io.*;
import org.gjt.sp.jedit.msg.*;
import org.gjt.sp.jedit.syntax.*;
import org.gjt.sp.jedit.textarea.*;
import org.gjt.sp.jedit.bufferio.BufferIORequest;
import org.gjt.sp.jedit.bufferio.BufferAutosaveRequest;
import org.gjt.sp.jedit.bufferio.MarkersSaveRequest;
import org.gjt.sp.util.*;
//}}}

/**
 * A <code>Buffer</code> represents the contents of an open text
 * file as it is maintained in the computer's memory (as opposed to
 * how it may be stored on a disk).<p>
 *
 * In a BeanShell script, you can obtain the current buffer instance from the
 * <code>buffer</code> variable.<p>
 *
 * This class does not have a public constructor.
 * Buffers can be opened and closed using methods in the <code>jEdit</code>
 * class.<p>
 *
 * This class is partially thread-safe, however you must pay attention to two
 * very important guidelines:
 * <ul>
 * <li>Changes to a buffer can only be made from the AWT thread.
 * <li>When accessing the buffer from another thread, you must
 * grab a read lock if you plan on performing more than one call, to ensure that
 * the buffer contents are not changed by the AWT thread for the duration of the
 * lock. Only methods whose descriptions specify thread safety can be invoked
 * from other threads.
 * </ul>
 *
 * @author Slava Pestov
 * @version $Id: Buffer.java 8340 2007-01-10 21:12:06Z kpouer $
 */
public class Buffer extends JEditBuffer
{
	//{{{ Some constants
	/**
	 * Backed up property.
	 * @since jEdit 3.2pre2
	 */
	public static final String BACKED_UP = "Buffer__backedUp";

	/**
	 * Caret info properties.
	 * @since jEdit 3.2pre1
	 */
	public static final String CARET = "Buffer__caret";
	public static final String CARET_POSITIONED = "Buffer__caretPositioned";

	/**
	 * Stores a List of {@link Selection} instances.
	 */
	public static final String SELECTION = "Buffer__selection";

	/**
	 * This should be a physical line number, so that the scroll
	 * position is preserved correctly across reloads (which will
	 * affect virtual line numbers, due to fold being reset)
	 */
	public static final String SCROLL_VERT = "Buffer__scrollVert";
	public static final String SCROLL_HORIZ = "Buffer__scrollHoriz";

	/**
	 * Should jEdit try to set the encoding based on a UTF8, UTF16 or
	 * XML signature at the beginning of the file?
	 */
	public static final String ENCODING_AUTODETECT = "encodingAutodetect";

	/**
	 * This property is set to 'true' if the file has a trailing newline.
	 * @since jEdit 4.0pre1
	 */
	public static final String TRAILING_EOL = "trailingEOL";

	/**
	 * This property is set to 'true' if the file should be GZipped.
	 * @since jEdit 4.0pre4
	 */
	public static final String GZIPPED = "gzipped";
	//}}}

	//{{{ Input/output methods

	//{{{ reload() method
	/**
	 * Reloads the buffer from disk, asking for confirmation if the buffer
	 * has unsaved changes.
	 * @param view The view
	 * @since jEdit 2.7pre2
	 */
	public void reload(View view)
	{
		if (getFlag(NEW_FILE))
			return;
		if(isDirty())
		{
			String[] args = { path };
			int result = GUIUtilities.confirm(view,"changedreload",
				args,JOptionPane.YES_NO_OPTION,
				JOptionPane.WARNING_MESSAGE);
			if(result != JOptionPane.YES_OPTION)
				return;
		}
		EditPane[] editPanes = view.getEditPanes();
		for (int i = 0; i < editPanes.length; i++)
			editPanes[i].saveCaretInfo();
		load(view,true);
	} //}}}

	//{{{ load() method
	/**
	 * Loads the buffer from disk.
	 * @param view The view
	 * @param reload If true, user will not be asked to recover autosave
	 * file, if any
	 *
	 * @since 2.5pre1
	 */
	public boolean load(final View view, final boolean reload)
	{
		if(isPerformingIO())
		{
			GUIUtilities.error(view,"buffer-multiple-io",null);
			return false;
		}

		setBooleanProperty(BufferIORequest.ERROR_OCCURRED,false);

		setLoading(true);

		// view text areas temporarily blank out while a buffer is
		// being loaded, to indicate to the user that there is no
		// data available yet.
		if(!getFlag(TEMPORARY))
			EditBus.send(new BufferUpdate(this,view,BufferUpdate.LOAD_STARTED));

		final boolean loadAutosave;

		if(reload || !getFlag(NEW_FILE))
		{
			if(file != null)
				modTime = file.lastModified();

			// Only on initial load
			if(!reload && autosaveFile != null && autosaveFile.exists())
				loadAutosave = recoverAutosave(view);
			else
			{
				if(autosaveFile != null)
					autosaveFile.delete();
				loadAutosave = false;
			}

			if(!loadAutosave)
			{
				VFS vfs = VFSManager.getVFSForPath(path);

				if(!checkFileForLoad(view,vfs,path))
				{
					setLoading(false);
					return false;
				}

				// have to check again since above might set
				// NEW_FILE flag
				if(reload || !getFlag(NEW_FILE))
				{
					if(!vfs.load(view,this,path))
					{
						setLoading(false);
						return false;
					}
				}
			}
		}
		else
			loadAutosave = false;

		//{{{ Do some stuff once loading is finished
		Runnable runnable = new Runnable()
		{
			public void run()
			{
				String newPath = getStringProperty(
					BufferIORequest.NEW_PATH);
				Segment seg = (Segment)getProperty(
					BufferIORequest.LOAD_DATA);
				IntegerArray endOffsets = (IntegerArray)
					getProperty(BufferIORequest.END_OFFSETS);

				loadText(seg,endOffsets);

				unsetProperty(BufferIORequest.LOAD_DATA);
				unsetProperty(BufferIORequest.END_OFFSETS);
				unsetProperty(BufferIORequest.NEW_PATH);

				undoMgr.clear();
				undoMgr.setLimit(jEdit.getIntegerProperty(
					"buffer.undoCount",100));

				if(!getFlag(TEMPORARY))
					finishLoading();

				setLoading(false);

				// if reloading a file, clear dirty flag
				if(reload)
					setDirty(false);

				if(!loadAutosave && newPath != null)
					setPath(newPath);

				// if loadAutosave is false, we loaded an
				// autosave file, so we set 'dirty' to true

				// note that we don't use setDirty(),
				// because a) that would send an unnecessary
				// message, b) it would also set the
				// AUTOSAVE_DIRTY flag, which will make
				// the autosave thread write out a
				// redundant autosave file
				if(loadAutosave)
					Buffer.super.setDirty(true);

				// send some EditBus messages
				if(!getFlag(TEMPORARY))
				{
					fireBufferLoaded();
					EditBus.send(new BufferUpdate(Buffer.this,
						view,BufferUpdate.LOADED));
					//EditBus.send(new BufferUpdate(Buffer.this,
					//	view,BufferUpdate.MARKERS_CHANGED));
				}
			}
		}; //}}}

		if(getFlag(TEMPORARY))
			runnable.run();
		else
			VFSManager.runInAWTThread(runnable);

		return true;
	} //}}}

	//{{{ insertFile() method
	/**
	 * Loads a file from disk, and inserts it into this buffer.
	 * @param view The view
	 *
	 * @since 4.0pre1
	 */
	public boolean insertFile(View view, String path)
	{
		if(isPerformingIO())
		{
			GUIUtilities.error(view,"buffer-multiple-io",null);
			return false;
		}

		setBooleanProperty(BufferIORequest.ERROR_OCCURRED,false);

		path = MiscUtilities.constructPath(this.path,path);

		Buffer buffer = jEdit.getBuffer(path);
		if(buffer != null)
		{
			view.getTextArea().setSelectedText(
				buffer.getText(0,buffer.getLength()));
			return true;
		}

		VFS vfs = VFSManager.getVFSForPath(path);

		// this returns false if initial sanity
		// checks (if the file is a directory, etc)
		// fail
		return vfs.insert(view,this,path);
	} //}}}

	//{{{ autosave() method
	/**
	 * Autosaves this buffer.
	 */
	public void autosave()
	{
		if(autosaveFile == null || !getFlag(AUTOSAVE_DIRTY)
			|| !isDirty() || isPerformingIO())
			return;

		setFlag(AUTOSAVE_DIRTY,false);

		VFSManager.runInWorkThread(new BufferAutosaveRequest(
			null,this,null,VFSManager.getFileVFS(),
			autosaveFile.getPath()));
	} //}}}

	//{{{ saveAs() method
	/**
	 * Prompts the user for a file to save this buffer to.
	 * @param view The view
	 * @param rename True if the buffer's path should be changed, false
	 * if only a copy should be saved to the specified filename
	 * @since jEdit 2.6pre5
	 */
	public boolean saveAs(View view, boolean rename)
	{
		String[] files = GUIUtilities.showVFSFileDialog(view,path,
			VFSBrowser.SAVE_DIALOG,false);

		// files[] should have length 1, since the dialog type is
		// SAVE_DIALOG
		if(files == null)
			return false;

		return save(view,files[0],rename);
	} //}}}

	//{{{ save() method
	/**
	 * Saves this buffer to the specified path name, or the current path
	 * name if it's null.
	 * @param view The view
	 * @param path The path name to save the buffer to, or null to use
	 * the existing path
	 */
	public boolean save(View view, String path)
	{
		return save(view,path,true);
	} //}}}

	//{{{ save() method
	/**
	 * Saves this buffer to the specified path name, or the current path
	 * name if it's null.
	 * @param view The view
	 * @param path The path name to save the buffer to, or null to use
	 * the existing path
	 * @param rename True if the buffer's path should be changed, false
	 * if only a copy should be saved to the specified filename
	 * @since jEdit 2.6pre5
	 */
	public boolean save(final View view, String path, final boolean rename)
	{
		if(isPerformingIO())
		{
			GUIUtilities.error(view,"buffer-multiple-io",null);
			return false;
		}

		setBooleanProperty(BufferIORequest.ERROR_OCCURRED,false);

		if(path == null && getFlag(NEW_FILE))
			return saveAs(view,rename);

		if(path == null && file != null)
		{
			long newModTime = file.lastModified();

			if(newModTime != modTime
				&& jEdit.getBooleanProperty("view.checkModStatus"))
			{
				Object[] args = { this.path };
				int result = GUIUtilities.confirm(view,
					"filechanged-save",args,
					JOptionPane.YES_NO_OPTION,
					JOptionPane.WARNING_MESSAGE);
				if(result != JOptionPane.YES_OPTION)
					return false;
			}
		}

		EditBus.send(new BufferUpdate(this,view,BufferUpdate.SAVING));

		setPerformingIO(true);

		final String oldPath = this.path;
		final String oldSymlinkPath = symlinkPath;
		final String newPath = path == null ? this.path : path;

		VFS vfs = VFSManager.getVFSForPath(newPath);

		if(!checkFileForSave(view,vfs,newPath))
		{
			setPerformingIO(false);
			return false;
		}

		Object session = vfs.createVFSSession(newPath,view);
		if (session == null)
		{
			setPerformingIO(false);
			return false;
		}

		unsetProperty("overwriteReadonly");
		unsetProperty("forbidTwoStageSave");
		try
		{
			VFSFile file = vfs._getFile(session,newPath,view);
			if (file != null)
			{
				boolean vfsRenameCap = (vfs.getCapabilities() & VFS.RENAME_CAP) != 0;
				if (!file.isWriteable())
				{
					Log.log(Log.WARNING, this, "Buffer saving : File " + file + " is readOnly");
					if (vfsRenameCap)
					{
						Log.log(Log.DEBUG, this, "Buffer saving : VFS can rename files");
						String savePath = vfs._canonPath(session,newPath,view);
						if(!MiscUtilities.isURL(savePath))
							savePath = MiscUtilities.resolveSymlinks(savePath);
						savePath = vfs.getTwoStageSaveName(savePath);
						if (savePath == null)
						{
							Log.log(Log.DEBUG, this, "Buffer saving : two stage save impossible because path is null");
							VFSManager.error(view,
								newPath,
								"ioerror.save-readonly-twostagefail",
								null);
							setPerformingIO(false);
							return false;
						}
						else
						{
							int result = GUIUtilities.confirm(
								view, "vfs.overwrite-readonly",
								new Object[]{newPath},
								JOptionPane.YES_NO_OPTION,
								JOptionPane.WARNING_MESSAGE);
							if (result == JOptionPane.YES_OPTION)
							{
								Log.log(Log.WARNING, this, "Buffer saving : two stage save will be used to save buffer");
								setBooleanProperty("overwriteReadonly",true);
							}
							else
							{
								Log.log(Log.DEBUG,this, "Buffer not saved");
								setPerformingIO(false);
								return false;
							}
						}
					}
					else
					{
						Log.log(Log.WARNING, this, "Buffer saving : file is readonly and vfs cannot do two stage save");
						VFSManager.error(view,
							newPath,
							"ioerror.write-error-readonly",
							null);
						setPerformingIO(false);
						return false;
					}
				}
				else
				{
					String savePath = vfs._canonPath(session,newPath,view);
					if(!MiscUtilities.isURL(savePath))
						savePath = MiscUtilities.resolveSymlinks(savePath);
					savePath = vfs.getTwoStageSaveName(savePath);
					if (jEdit.getBooleanProperty("twoStageSave") && (!vfsRenameCap || savePath == null))
					{
						// the file is writeable but the vfs cannot do two stage. We must overwrite
						// readonly flag


						int result = GUIUtilities.confirm(
								view, "vfs.twostageimpossible",
								new Object[]{newPath},
								JOptionPane.YES_NO_OPTION,
								JOptionPane.WARNING_MESSAGE);
						if (result == JOptionPane.YES_OPTION)
						{
							Log.log(Log.WARNING, this, "Buffer saving : two stage save cannot be used");
							setBooleanProperty("forbidTwoStageSave",true);
						}
						else
						{
							Log.log(Log.DEBUG,this, "Buffer not saved");
							setPerformingIO(false);
							return false;
						}

					}
				}
			}
		}
		catch(IOException io)
		{
			VFSManager.error(view,newPath,"ioerror",
				new String[] { io.toString() });
			setPerformingIO(false);
			return false;
		}
		finally
		{
			try
			{
				vfs._endVFSSession(session,view);
			}
			catch(IOException io)
			{
				VFSManager.error(view,newPath,"ioerror",
					new String[] { io.toString() });
				setPerformingIO(false);
				return false;
			}
		}

		if(!vfs.save(view,this,newPath))
		{
			setPerformingIO(false);
			return false;
		}

		// Once save is complete, do a few other things
		VFSManager.runInAWTThread(new Runnable()
			{
				public void run()
				{
					setPerformingIO(false);
					setProperty("overwriteReadonly",null);
					finishSaving(view,oldPath,oldSymlinkPath,
						newPath,rename,getBooleanProperty(
							BufferIORequest.ERROR_OCCURRED));
					updateMarkersFile(view);
				}
			});

		return true;
	} //}}}

	//{{{ checkFileStatus() method
	public static final int FILE_NOT_CHANGED = 0;
	public static final int FILE_CHANGED = 1;
	public static final int FILE_DELETED = 2;
	/**
	 * Check if the buffer has changed on disk.
	 * @return One of <code>NOT_CHANGED</code>, <code>CHANGED</code>, or
	 * <code>DELETED</code>.
	 *
	 * @since jEdit 4.2pre1
	 */
	public int checkFileStatus(View view)
	{
		// - don't do these checks while a save is in progress,
		// because for a moment newModTime will be greater than
		// oldModTime, due to the multithreading
		// - only supported on local file system
		if(!isPerformingIO() && file != null && !getFlag(NEW_FILE))
		{
			boolean newReadOnly = (file.exists() && !file.canWrite());
			if(newReadOnly != isFileReadOnly())
			{
				setFileReadOnly(newReadOnly);
				EditBus.send(new BufferUpdate(this,null,
					BufferUpdate.DIRTY_CHANGED));
			}

			long oldModTime = modTime;
			long newModTime = file.lastModified();

			if(newModTime != oldModTime)
			{
				modTime = newModTime;

				if(!file.exists())
				{
					setFlag(NEW_FILE,true);
					setDirty(true);
					return FILE_DELETED;
				}
				else
				{
					return FILE_CHANGED;
				}
			}
		}

		return FILE_NOT_CHANGED;
	} //}}}

	//}}}

	//{{{ Getters/setter methods for various buffer meta-data

	//{{{ getLastModified() method
	/**
	 * Returns the last time jEdit modified the file on disk.
	 * This method is thread-safe.
	 */
	public long getLastModified()
	{
		return modTime;
	} //}}}

	//{{{ setLastModified() method
	/**
	 * Sets the last time jEdit modified the file on disk.
	 * @param modTime The new modification time
	 */
	public void setLastModified(long modTime)
	{
		this.modTime = modTime;
	} //}}}

	//{{{ getAutoReload() method
	/**
	 * Returns the status of the AUTORELOAD flag
	 * If true, reload changed files automatically
	 */
	public boolean getAutoReload()
	{
		return getFlag(AUTORELOAD);
	} //}}}

	//{{{ setAutoReload() method
	/**
	 * Sets the status of the AUTORELOAD flag
	 * @param value # If true, reload changed files automatically
	 */
	public void setAutoReload(boolean value)
	{
		setFlag(AUTORELOAD, value);
	} //}}}

	//{{{ getAutoReloadDialog() method
	/**
	 * Returns the status of the AUTORELOAD_DIALOG flag
	 * If true, prompt for reloading or notify user
	 * when the file has changed on disk
	 */
	public boolean getAutoReloadDialog()
	{
		return getFlag(AUTORELOAD_DIALOG);
	} //}}}

	//{{{ setAutoReloadDialog() method
	/**
	 * Sets the status of the AUTORELOAD_DIALOG flag
	 * @param value # If true, prompt for reloading or notify user
	 * when the file has changed on disk

	 */
	public void setAutoReloadDialog(boolean value)
	{
		setFlag(AUTORELOAD_DIALOG, value);
	} //}}}

	//{{{ getVFS() method
	/**
	 * Returns the virtual filesystem responsible for loading and
	 * saving this buffer. This method is thread-safe.
	 */
	public VFS getVFS()
	{
		return VFSManager.getVFSForPath(path);
	} //}}}

	//{{{ getAutosaveFile() method
	/**
	 * Returns the autosave file for this buffer. This may be null if
	 * the file is non-local.
	 */
	public File getAutosaveFile()
	{
		return autosaveFile;
	} //}}}

	//{{{ getName() method
	/**
	 * Returns the name of this buffer. This method is thread-safe.
	 */
	public String getName()
	{
		return name;
	} //}}}

	//{{{ getPath() method
	/**
	 * Returns the path name of this buffer. This method is thread-safe.
	 */
	public String getPath()
	{
		return path;
	} //}}}

	//{{{ getSymlinkPath() method
	/**
	 * If this file is a symbolic link, returns the link destination.
	 * Otherwise returns the file's path. This method is thread-safe.
	 * @since jEdit 4.2pre1
	 */
	public String getSymlinkPath()
	{
		return symlinkPath;
	} //}}}

	//{{{ getDirectory() method
	/**
	 * Returns the directory containing this buffer.
	 * @since jEdit 4.1pre11
	 */
	public String getDirectory()
	{
		return directory;
	} //}}}

	//{{{ isClosed() method
	/**
	 * Returns true if this buffer has been closed with
	 * {@link org.gjt.sp.jedit.jEdit#closeBuffer(View,Buffer)}.
	 * This method is thread-safe.
	 */
	public boolean isClosed()
	{
		return getFlag(CLOSED);
	} //}}}

	//{{{ isLoaded() method
	/**
	 * Returns true if the buffer is loaded. This method is thread-safe.
	 */
	public boolean isLoaded()
	{
		return !isLoading();
	} //}}}

	//{{{ isNewFile() method
	/**
	 * Returns whether this buffer lacks a corresponding version on disk.
	 * This method is thread-safe.
	 */
	public boolean isNewFile()
	{
		return getFlag(NEW_FILE);
	} //}}}

	//{{{ setNewFile() method
	/**
	 * Sets the new file flag.
	 * @param newFile The new file flag
	 */
	public void setNewFile(boolean newFile)
	{
		setFlag(NEW_FILE,newFile);
		if(!newFile)
			setFlag(UNTITLED,false);
	} //}}}

	//{{{ isUntitled() method
	/**
	 * Returns true if this file is 'untitled'. This method is thread-safe.
	 */
	public boolean isUntitled()
	{
		return getFlag(UNTITLED);
	} //}}}

	//{{{ setDirty() method
	/**
	 * Sets the 'dirty' (changed since last save) flag of this buffer.
	 */
	public void setDirty(boolean d)
	{
		boolean old_d = isDirty();
		super.setDirty(d);
		boolean editable = isEditable();

		if(d)
		{
			if(editable)
				setFlag(AUTOSAVE_DIRTY,true);
		}
		else
		{
			setFlag(AUTOSAVE_DIRTY,false);

			if(autosaveFile != null)
				autosaveFile.delete();
		}

		if(d != old_d && editable)
		{
			EditBus.send(new BufferUpdate(this,null,
				BufferUpdate.DIRTY_CHANGED));
		}
	} //}}}

	//{{{ isTemporary() method
	/**
	 * Returns if this is a temporary buffer. This method is thread-safe.
	 * @see jEdit#openTemporary(View,String,String,boolean)
	 * @see jEdit#commitTemporary(Buffer)
	 * @since jEdit 2.2pre7
	 */
	public boolean isTemporary()
	{
		return getFlag(TEMPORARY);
	} //}}}

	//{{{ getIcon() method
	/**
	 * Returns this buffer's icon.
	 * @since jEdit 2.6pre6
	 */
	public Icon getIcon()
	{
		if(isDirty())
			return GUIUtilities.loadIcon("dirty.gif");
		else if(isReadOnly())
			return GUIUtilities.loadIcon("readonly.gif");
		else if(getFlag(NEW_FILE))
			return GUIUtilities.loadIcon("new.gif");
		else
			return GUIUtilities.loadIcon("normal.gif");
	} //}}}

	//}}}

	//{{{ Buffer events

	//{{{ addBufferChangeListener() method
	/**
	 * @deprecated Call {@link JEditBuffer#addBufferListener(BufferListener,int)}.
	 */
	public void addBufferChangeListener(BufferChangeListener listener,
		int priority)
	{
		addBufferListener(new BufferChangeListener.Adapter(listener),priority);
	} //}}}

	//{{{ addBufferChangeListener() method
	/**
	 * @deprecated Call {@link JEditBuffer#addBufferListener(BufferListener)}.
	 */
	public void addBufferChangeListener(BufferChangeListener listener)
	{
		addBufferChangeListener(listener,NORMAL_PRIORITY);
	} //}}}

	//{{{ removeBufferChangeListener() method
	/**
	 * @deprecated Call {@link JEditBuffer#removeBufferListener(BufferListener)}.
	 */
	public void removeBufferChangeListener(BufferChangeListener listener)
	{
		BufferListener[] listeners = getBufferListeners();

		for(int i = 0; i < listeners.length; i++)
		{
			BufferListener l = listeners[i];
			if(l instanceof BufferChangeListener.Adapter)
			{
				if(((BufferChangeListener.Adapter)l).getDelegate() == listener)
				{
					removeBufferListener(l);
					return;
				}
			}
		}
	} //}}}

	//}}}

	//{{{ Property methods

	//{{{ propertiesChanged() method
	/**
	 * Reloads settings from the properties. This should be called
	 * after the <code>syntax</code> or <code>folding</code>
	 * buffer-local properties are changed.
	 */
	public void propertiesChanged()
	{
		String folding = getStringProperty("folding");
		FoldHandler handler = FoldHandler.getFoldHandler(folding);

		if(handler != null)
		{
			setFoldHandler(handler);
		}
		else
		{
			if (folding != null)
				Log.log(Log.WARNING, this, path + ": invalid 'folding' property: " + folding);
			setFoldHandler(new DummyFoldHandler());
		}

		EditBus.send(new BufferUpdate(this,null,BufferUpdate.PROPERTIES_CHANGED));
	} //}}}

	//{{{ getDefaultProperty() method
	public Object getDefaultProperty(String name)
	{
		Object retVal;

		if(mode != null)
		{
			retVal = mode.getProperty(name);
			if(retVal == null)
				return null;

			setDefaultProperty(name,retVal);
			return retVal;
		}
		// Now try buffer.<property>
		String value = jEdit.getProperty("buffer." + name);
		if(value == null)
			return null;

		// Try returning it as an integer first
		try
		{
			retVal = new Integer(value);
		}
		catch(NumberFormatException nf)
		{
			retVal = value;
		}

		return retVal;
	} //}}}

	//{{{ toggleWordWrap() method
	/**
	 * Toggles word wrap between the three available modes. This is used
	 * by the status bar.
	 * @param view We show a message in the view's status bar
	 * @since jEdit 4.1pre3
	 */
	public void toggleWordWrap(View view)
	{
		String wrap = getStringProperty("wrap");
		if(wrap.equals("none"))
			wrap = "soft";
		else if(wrap.equals("soft"))
			wrap = "hard";
		else if(wrap.equals("hard"))
			wrap = "none";
		view.getStatus().setMessageAndClear(jEdit.getProperty(
			"view.status.wrap-changed",new String[] {
			wrap }));
		setProperty("wrap",wrap);
		propertiesChanged();
	} //}}}

	//{{{ toggleLineSeparator() method
	/**
	 * Toggles the line separator between the three available settings.
	 * This is used by the status bar.
	 * @param view We show a message in the view's status bar
	 * @since jEdit 4.1pre3
	 */
	public void toggleLineSeparator(View view)
	{
		String status = null;
		String lineSep = getStringProperty("lineSeparator");
		if("\n".equals(lineSep))
		{
			status = "windows";
			lineSep = "\r\n";
		}
		else if("\r\n".equals(lineSep))
		{
			status = "mac";
			lineSep = "\r";
		}
		else if("\r".equals(lineSep))
		{
			status = "unix";
			lineSep = "\n";
		}
		view.getStatus().setMessageAndClear(jEdit.getProperty(
			"view.status.linesep-changed",new String[] {
			jEdit.getProperty("lineSep." + status) }));
		setProperty("lineSeparator",lineSep);
		setDirty(true);
		propertiesChanged();
	} //}}}

	//{{{ getContextSensitiveProperty() method
	/**
	 * Some settings, like comment start and end strings, can
	 * vary between different parts of a buffer (HTML text and inline
	 * JavaScript, for example).
	 * @param offset The offset
	 * @param name The property name
	 * @since jEdit 4.0pre3
	 */
	public String getContextSensitiveProperty(int offset, String name)
	{
		Object value = super.getContextSensitiveProperty(offset,name);

		if(value == null)
		{
			ParserRuleSet rules = getRuleSetAtOffset(offset);

			value = jEdit.getMode(rules.getModeName())
				.getProperty(name);

			if(value == null)
				value = mode.getProperty(name);
		}

		if(value == null)
			return null;
		else
			return String.valueOf(value);
	} //}}}

	//}}}

	//{{{ Edit modes, syntax highlighting

	//{{{ getMode() method
	/**
	 * Returns this buffer's edit mode. This method is thread-safe.
	 */
	public Mode getMode()
	{
		return mode;
	} //}}}

	//{{{ setMode() method
	/**
	 * Sets this buffer's edit mode. Note that calling this before a buffer
	 * is loaded will have no effect; in that case, set the "mode" property
	 * to the name of the mode. A bit inelegant, I know...
	 * @param mode The mode name
	 * @since jEdit 4.2pre1
	 */
	public void setMode(String mode)
	{
		setMode(jEdit.getMode(mode));
	} //}}}

	//{{{ setMode() method
	/**
	 * Sets this buffer's edit mode. Note that calling this before a buffer
	 * is loaded will have no effect; in that case, set the "mode" property
	 * to the name of the mode. A bit inelegant, I know...
	 * @param mode The mode
	 */
	public void setMode(Mode mode)
	{
		/* This protects against stupid people (like me)
		 * doing stuff like buffer.setMode(jEdit.getMode(...)); */
		if(mode == null)
			throw new NullPointerException("Mode must be non-null");

		this.mode = mode;

		textMode = "text".equals(mode.getName());

		setTokenMarker(mode.getTokenMarker());

		resetCachedProperties();
		propertiesChanged();
	} //}}}

	//{{{ setMode() method
	/**
	 * Sets this buffer's edit mode by calling the accept() method
	 * of each registered edit mode.
	 */
	public void setMode()
	{
		String userMode = getStringProperty("mode");
		if(userMode != null)
		{
			Mode m = jEdit.getMode(userMode);
			if(m != null)
			{
				setMode(m);
				return;
			}
		}

		String nogzName = name.substring(0,name.length() -
			(name.endsWith(".gz") ? 3 : 0));
		Mode[] modes = jEdit.getModes();

		String firstLine = getLineText(0);

		// this must be in reverse order so that modes from the user
		// catalog get checked first!
		for(int i = modes.length - 1; i >= 0; i--)
		{
			if(modes[i].accept(nogzName,firstLine))
			{
				setMode(modes[i]);
				return;
			}
		}

		Mode defaultMode = jEdit.getMode(jEdit.getProperty("buffer.defaultMode"));
		if(defaultMode == null)
			defaultMode = jEdit.getMode("text");
		setMode(defaultMode);
	} //}}}

	//}}}

	//{{{ Deprecated methods

	//{{{ putProperty() method
	/**
	 * @deprecated Call <code>setProperty()</code> instead.
	 */
	public void putProperty(Object name, Object value)
	{
		// for backwards compatibility
		if(!(name instanceof String))
			return;

		setProperty((String)name,value);
	} //}}}

	//{{{ putBooleanProperty() method
	/**
	 * @deprecated Call <code>setBooleanProperty()</code> instead
	 */
	public void putBooleanProperty(String name, boolean value)
	{
		setBooleanProperty(name,value);
	} //}}}

	//{{{ markTokens() method
	/**
	 * @deprecated Use org.gjt.sp.jedit.syntax.DefaultTokenHandler instead
	 */
	public static class TokenList extends DefaultTokenHandler
	{
		public Token getFirstToken()
		{
			return getTokens();
		}
	}

	/**
	 * @deprecated Use the other form of <code>markTokens()</code> instead
	 */
	public TokenList markTokens(int lineIndex)
	{
		TokenList list = new TokenList();
		markTokens(lineIndex,list);
		return list;
	} //}}}

	//{{{ insertString() method
	/**
	 * @deprecated Call <code>insert()</code> instead.
	 */
	public void insertString(int offset, String str, AttributeSet attr)
	{
		insert(offset,str);
	} //}}}

	//{{{ getFile() method
	/**
	 * @deprecated Do not call this method, use {@link #getPath()}
	 * instead.
	 */
	public File getFile()
	{
		return file;
	} //}}}

	//}}}

	//{{{ Marker methods

	//{{{ getMarkers() method
	/**
	 * Returns a vector of markers.
	 * @since jEdit 3.2pre1
	 */
	public Vector<Marker> getMarkers()
	{
		return markers;
	} //}}}

	//{{{ getMarkerStatusPrompt() method
	/**
	 * Returns the status prompt for the given marker action. Only
	 * intended to be called from <code>actions.xml</code>.
	 * @since jEdit 4.2pre2
	 */
	public String getMarkerStatusPrompt(String action)
	{
		return jEdit.getProperty("view.status." + action,
			new String[] { getMarkerNameString() });
	} //}}}

	//{{{ getMarkerNameString() method
	/**
	 * Returns a string of all set markers, used by the status bar
	 * (eg, "a b $ % ^").
	 * @since jEdit 4.2pre2
	 */
	public String getMarkerNameString()
	{
		StringBuilder buf = new StringBuilder();
		for(int i = 0; i < markers.size(); i++)
		{
			Marker marker = markers.get(i);
			if(marker.getShortcut() != '\0')
			{
				if(buf.length() != 0)
					buf.append(' ');
				buf.append(marker.getShortcut());
			}
		}

		if(buf.length() == 0)
			return jEdit.getProperty("view.status.no-markers");
		else
			return buf.toString();
	} //}}}

	//{{{ addOrRemoveMarker() method
	/**
	 * If a marker is set on the line of the position, it is removed. Otherwise
	 * a new marker with the specified shortcut is added.
	 * @param pos The position of the marker
	 * @param shortcut The shortcut ('\0' if none)
	 * @since jEdit 3.2pre5
	 */
	public void addOrRemoveMarker(char shortcut, int pos)
	{
		int line = getLineOfOffset(pos);
		if(getMarkerAtLine(line) != null)
			removeMarker(line);
		else
			addMarker(shortcut,pos);
	} //}}}

	//{{{ addMarker() method
	/**
	 * Adds a marker to this buffer.
	 * @param pos The position of the marker
	 * @param shortcut The shortcut ('\0' if none)
	 * @since jEdit 3.2pre1
	 */
	public void addMarker(char shortcut, int pos)
	{
		Marker markerN = new Marker(this,shortcut,pos);
		boolean added = false;

		// don't sort markers while buffer is being loaded
		if(isLoaded())
		{
			setFlag(MARKERS_CHANGED,true);

			markerN.createPosition();

			for(int i = 0; i < markers.size(); i++)
			{
				Marker marker = markers.get(i);
				if(shortcut != '\0' && marker.getShortcut() == shortcut)
					marker.setShortcut('\0');

				if(marker.getPosition() == pos)
				{
					markers.removeElementAt(i);
					i--;
				}
			}

			for(int i = 0; i < markers.size(); i++)
			{
				Marker marker = markers.get(i);
				if(marker.getPosition() > pos)
				{
					markers.insertElementAt(markerN,i);
					added = true;
					break;
				}
			}
		}

		if(!added)
			markers.addElement(markerN);

		if(isLoaded() && !getFlag(TEMPORARY))
		{
			EditBus.send(new BufferUpdate(this,null,
				BufferUpdate.MARKERS_CHANGED));
		}
	} //}}}

	//{{{ getMarkerInRange() method
	/**
	 * Returns the first marker within the specified range.
	 * @param start The start offset
	 * @param end The end offset
	 * @since jEdit 4.0pre4
	 */
	public Marker getMarkerInRange(int start, int end)
	{
		for(int i = 0; i < markers.size(); i++)
		{
			Marker marker = markers.get(i);
			int pos = marker.getPosition();
			if(pos >= start && pos < end)
				return marker;
		}

		return null;
	} //}}}

	//{{{ getMarkerAtLine() method
	/**
	 * Returns the first marker at the specified line, or <code>null</code>
	 * if there is none.
	 * @param line The line number
	 * @since jEdit 3.2pre2
	 */
	public Marker getMarkerAtLine(int line)
	{
		for(int i = 0; i < markers.size(); i++)
		{
			Marker marker = markers.get(i);
			if(getLineOfOffset(marker.getPosition()) == line)
				return marker;
		}

		return null;
	} //}}}

	//{{{ removeMarker() method
	/**
	 * Removes all markers at the specified line.
	 * @param line The line number
	 * @since jEdit 3.2pre2
	 */
	public void removeMarker(int line)
	{
		for(int i = 0; i < markers.size(); i++)
		{
			Marker marker = markers.get(i);
			if(getLineOfOffset(marker.getPosition()) == line)
			{
				setFlag(MARKERS_CHANGED,true);
				marker.removePosition();
				markers.removeElementAt(i);
				i--;
			}
		}

		EditBus.send(new BufferUpdate(this,null,
			BufferUpdate.MARKERS_CHANGED));
	} //}}}

	//{{{ removeAllMarkers() method
	/**
	 * Removes all defined markers.
	 * @since jEdit 2.6pre1
	 */
	public void removeAllMarkers()
	{
		setFlag(MARKERS_CHANGED,true);

		for(int i = 0; i < markers.size(); i++)
			markers.get(i).removePosition();

		markers.removeAllElements();

		if(isLoaded())
		{
			EditBus.send(new BufferUpdate(this,null,
				BufferUpdate.MARKERS_CHANGED));
		}
	} //}}}

	//{{{ getMarker() method
	/**
	 * Returns the marker with the specified shortcut.
	 * @param shortcut The shortcut
	 * @since jEdit 3.2pre2
	 */
	public Marker getMarker(char shortcut)
	{
		Enumeration<Marker> e = markers.elements();
		while(e.hasMoreElements())
		{
			Marker marker = e.nextElement();
			if(marker.getShortcut() == shortcut)
				return marker;
		}
		return null;
	} //}}}

	//{{{ getMarkersPath() method
	/**
	 * Returns the path for this buffer's markers file
	 * @param vfs The appropriate VFS
	 * @since jEdit 4.3pre7
	 */
	public String getMarkersPath(VFS vfs)
	{
		return vfs.getParentOfPath(path)
			+ '.' + vfs.getFileName(path)
			+ ".marks";
	} //}}}

	//{{{ updateMarkersFile() method
	/**
	 * Save the markers file, or delete it when there are mo markers left
	 * Handling markers is now independent from saving the buffer.
	 * Changing markers will not set the buffer dirty any longer.
	 * @param view The current view
	 * @since jEdit 4.3pre7
	 */
	public boolean updateMarkersFile(View view)
	{
		if(!markersChanged())
			return true;
		// adapted from VFS.save
		VFS vfs = VFSManager.getVFSForPath(getPath());
		if ((vfs.getCapabilities() & VFS.WRITE_CAP) == 0) {
			VFSManager.error(view, path, "vfs.not-supported.save",
				new String[] { "markers file" });
			return false;
			}
		Object session = vfs.createVFSSession(path, view);
		if(session == null)
			return false;
		VFSManager.runInWorkThread(
			new MarkersSaveRequest(
				view, this, session, vfs, path));
		return true;
	} //}}}

	//{{{ markersChanged() method
	/**
	 * Return true when markers have changed and the markers file needs
	 * to be updated
	 * @since jEdit 4.3pre7
	 */
	public boolean markersChanged()
	{
		return getFlag(MARKERS_CHANGED);
	} //}}}

	//{{{ setMarkersChanged() method
	/**
	 * Sets/unsets the MARKERS_CHANGED flag
	 * @since jEdit 4.3pre7
	 */
	public void setMarkersChanged(boolean changed)
	{
		setFlag(MARKERS_CHANGED, changed);
	} //}}}

	//}}}

	//{{{ Miscellaneous methods

	//{{{ setWaitSocket() method
	/**
	 * This socket is closed when the buffer is closed.
	 */
	public void setWaitSocket(Socket waitSocket)
	{
		this.waitSocket = waitSocket;
	} //}}}

	//{{{ getNext() method
	/**
	 * Returns the next buffer in the list.
	 */
	public Buffer getNext()
	{
		return next;
	} //}}}

	//{{{ getPrev() method
	/**
	 * Returns the previous buffer in the list.
	 */
	public Buffer getPrev()
	{
		return prev;
	} //}}}

	//{{{ getIndex() method
	/**
	 * Returns the position of this buffer in the buffer list.
	 */
	public int getIndex()
	{
		int count = 0;
		Buffer buffer = prev;
		while (true)
		{
			if(buffer == null)
				break;
			count++;
			buffer = buffer.prev;
		}
		return count;
	} //}}}

	//{{{ toString() method
	/**
	 * Returns a string representation of this buffer.
	 * This simply returns the path name.
	 */
	public String toString()
	{
		return name + " (" + directory + ')';
	} //}}}

	//}}}

	//{{{ Package-private members
	/** The previous buffer in the list. */
	Buffer prev;
	/** The next buffer in the list. */
	Buffer next;

	//{{{ Buffer constructor
	Buffer(String path, boolean newFile, boolean temp, Hashtable props)
	{
		super(props);

		markers = new Vector<Marker>();

		setFlag(TEMPORARY,temp);

		// this must be called before any EditBus messages are sent
		setPath(path);

		/* Magic: UNTITLED is only set if newFile param to
		 * constructor is set, NEW_FILE is also set if file
		 * doesn't exist on disk.
		 *
		 * This is so that we can tell apart files created
		 * with jEdit.newFile(), and those that just don't
		 * exist on disk.
		 *
		 * Why do we need to tell the difference between the
		 * two? jEdit.addBufferToList() checks if the only
		 * opened buffer is an untitled buffer, and if so,
		 * replaces it with the buffer to add. We don't want
		 * this behavior to occur with files that don't
		 * exist on disk; only untitled ones.
		 */
		setFlag(UNTITLED,newFile);
		setFlag(NEW_FILE,newFile);
		setFlag(AUTORELOAD,jEdit.getBooleanProperty("autoReload"));
		setFlag(AUTORELOAD_DIALOG,jEdit.getBooleanProperty("autoReloadDialog"));
	} //}}}
	
	/*
	//Buffer constructor to clone buffer
	public Buffer(Buffer other){

		this.markers = other.markers;
		this.setFlag(TEMPORARY, other.getFlag(TEMPORARY));
		this.setPath(other.getPath());
		this.setFlag(UNTITLED, true);
		this.setFlag(NEW_FILE, true);
		this.setFlag(AUTORELOAD,jEdit.getBooleanProperty("autoReload"));
		this.setFlag(AUTORELOAD_DIALOG,jEdit.getBooleanProperty("autoReloadDialog"));
	
	}
	*/
	//{{{ commitTemporary() method
	void commitTemporary()
	{
		setFlag(TEMPORARY,false);

		finishLoading();
	} //}}}

	//{{{ close() method
	void close()
	{
		setFlag(CLOSED,true);

		if(autosaveFile != null)
			autosaveFile.delete();

		// notify clients with -wait
		if(waitSocket != null)
		{
			try
			{
				waitSocket.getOutputStream().write('\0');
				waitSocket.getOutputStream().flush();
				waitSocket.getInputStream().close();
				waitSocket.getOutputStream().close();
				waitSocket.close();
			}
			catch(IOException io)
			{
				//Log.log(Log.ERROR,this,io);
			}
		}
	} //}}}

	//}}}

	//{{{ Private members

	//{{{ Flags

	//{{{ setFlag() method
	private void setFlag(int flag, boolean value)
	{
		if(value)
			flags |= (1 << flag);
		else
			flags &= ~(1 << flag);
	} //}}}

	//{{{ getFlag() method
	private boolean getFlag(int flag)
	{
		int mask = (1 << flag);
		return (flags & mask) == mask;
	} //}}}

	//{{{ Flag values
	private static final int CLOSED = 0;
	private static final int NEW_FILE = 3;
	private static final int UNTITLED = 4;
	private static final int AUTOSAVE_DIRTY = 5;
	private static final int AUTORELOAD = 6;
	private static final int AUTORELOAD_DIALOG = 7;
	private static final int TEMPORARY = 10;
	private static final int MARKERS_CHANGED = 12;
	//}}}

	private int flags;

	//}}}

	//{{{ Instance variables
	private String path;
	private String symlinkPath;
	private String name;
	private String directory;
	private File file;
	private File autosaveFile;
	private long modTime;
	private Mode mode;

	private final Vector<Marker> markers;

	private Socket waitSocket;
	//}}}

	//{{{ setPath() method
	private void setPath(String path)
	{
		View[] views = jEdit.getViews();
		for (int i = 0; i < views.length; i++)
		{
			View view = views[i];
			EditPane[] editPanes = view.getEditPanes();
			for (int j = 0; j < editPanes.length; j++)
				editPanes[j].bufferRenamed(this.path, path);
		}

		this.path = path;
		VFS vfs = VFSManager.getVFSForPath(path);
		if((vfs.getCapabilities() & VFS.WRITE_CAP) == 0)
			setFileReadOnly(true);
		name = vfs.getFileName(path);
		directory = vfs.getParentOfPath(path);

		if(vfs instanceof FileVFS)
		{
			file = new File(path);
			symlinkPath = MiscUtilities.resolveSymlinks(path);

			// if we don't do this, the autosave file won't be
			// deleted after a save as
			if(autosaveFile != null)
				autosaveFile.delete();
			autosaveFile = new File(file.getParent(),'#' + name + '#');
		}
		else
		{
			// I wonder if the lack of this broke anything in the
			// past?
			file = null;
			autosaveFile = null;
			symlinkPath = path;
		}
	} //}}}

	//{{{ recoverAutosave() method
	private boolean recoverAutosave(final View view)
	{
		if(!autosaveFile.canRead())
			return false;

		// this method might get called at startup
		GUIUtilities.hideSplashScreen();

		final Object[] args = { autosaveFile.getPath() };
		int result = GUIUtilities.confirm(view,"autosave-found",args,
			JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE);

		if(result == JOptionPane.YES_OPTION)
		{
			VFSManager.getFileVFS().load(view,this,autosaveFile.getPath());

			// show this message when all I/O requests are
			// complete
			VFSManager.runInAWTThread(new Runnable()
			{
				public void run()
				{
					GUIUtilities.message(view,"autosave-loaded",args);
				}
			});

			return true;
		}
		else
			return false;
	} //}}}

	//{{{ checkFileForLoad() method
	private boolean checkFileForLoad(View view, VFS vfs, String path)
	{
		if((vfs.getCapabilities() & VFS.LOW_LATENCY_CAP) != 0)
		{
			Object session = vfs.createVFSSession(path,view);
			if(session == null)
				return false;

			try
			{
				VFSFile file = vfs._getFile(session,path,view);
				if(file == null)
				{
					setNewFile(true);
					return true;
				}

				if(!file.isReadable())
				{
					VFSManager.error(view,path,"ioerror.no-read",null);
					setNewFile(false);
					return false;
				}

				setFileReadOnly(!file.isWriteable());

				if(file.getType() != VFSFile.FILE)
				{
					VFSManager.error(view,path,
						"ioerror.open-directory",null);
					setNewFile(false);
					return false;
				}
			}
			catch(IOException io)
			{
				VFSManager.error(view,path,"ioerror",
					new String[] { io.toString() });
				return false;
			}
			finally
			{
				try
				{
					vfs._endVFSSession(session,view);
				}
				catch(IOException io)
				{
					VFSManager.error(view,path,"ioerror",
						new String[] { io.toString() });
					return false;
				}
			}
		}

		return true;
	} //}}}

	//{{{ checkFileForSave() method
	private static boolean checkFileForSave(View view, VFS vfs, String path)
	{
		if((vfs.getCapabilities() & VFS.LOW_LATENCY_CAP) != 0)
		{
			Object session = vfs.createVFSSession(path,view);
			if(session == null)
				return false;

			try
			{
				VFSFile file = vfs._getFile(session,path,view);
				if(file == null)
					return true;

				if(file.getType() != VFSFile.FILE)
				{
					VFSManager.error(view,path,
						"ioerror.save-directory",null);
					return false;
				}
			}
			catch(IOException io)
			{
				VFSManager.error(view,path,"ioerror",
					new String[] { io.toString() });
				return false;
			}
			finally
			{
				try
				{
					vfs._endVFSSession(session,view);
				}
				catch(IOException io)
				{
					VFSManager.error(view,path,"ioerror",
						new String[] { io.toString() });
					return false;
				}
			}
		}

		return true;
	} //}}}

	//{{{ finishLoading() method
	private void finishLoading()
	{
		parseBufferLocalProperties();
		// AHA!
		// this is probably the only way to fix this
		FoldHandler oldFoldHandler = getFoldHandler();
		setMode();

		if(getFoldHandler() == oldFoldHandler)
		{
			// on a reload, the fold handler doesn't change, but
			// we still need to re-collapse folds.
			// don't do this on initial fold handler creation
			invalidateFoldLevels();

			fireFoldHandlerChanged();
		}

		// Create marker positions
		for(int i = 0; i < markers.size(); i++)
		{
			Marker marker = markers.get(i);
			marker.removePosition();
			int pos = marker.getPosition();
			if(pos > getLength())
				marker.setPosition(getLength());
			else if(pos < 0)
				marker.setPosition(0);
			marker.createPosition();
		}
	} //}}}

	//{{{ finishSaving() method
	private void finishSaving(View view, String oldPath,
		String oldSymlinkPath, String path,
		boolean rename, boolean error)
	{
		//{{{ Set the buffer's path
		// Caveat: won't work if save() called with a relative path.
		// But I don't think anyone calls it like that anyway.
		if(!error && !path.equals(oldPath))
		{
			Buffer buffer = jEdit.getBuffer(path);

			if(rename)
			{
				/* if we save a file with the same name as one
				 * that's already open, we presume that we can
				 * close the existing file, since the user
				 * would have confirmed the overwrite in the
				 * 'save as' dialog box anyway */
				if(buffer != null && /* can't happen? */
					!buffer.getPath().equals(oldPath))
				{
					buffer.setDirty(false);
					jEdit.closeBuffer(view,buffer);
				}

				setPath(path);
			}
			else
			{
				/* if we saved over an already open file using
				 * 'save a copy as', then reload the existing
				 * buffer */
				if(buffer != null && /* can't happen? */
					!buffer.getPath().equals(oldPath))
				{
					buffer.load(view,true);
				}
			}
		} //}}}

		//{{{ Update this buffer for the new path
		if(rename)
		{
			if(file != null)
				modTime = file.lastModified();

			if(!error)
			{
				// we do a write lock so that the
				// autosave, which grabs a read lock,
				// is not executed between the
				// deletion of the autosave file
				// and clearing of the dirty flag
				try
				{
					writeLock();

					if(autosaveFile != null)
						autosaveFile.delete();

					setFlag(AUTOSAVE_DIRTY,false);
					setFileReadOnly(false);
					setFlag(NEW_FILE,false);
					setFlag(UNTITLED,false);
					super.setDirty(false);

					// this ensures that undo can clear
					// the dirty flag properly when all
					// edits up to a save are undone
					undoMgr.bufferSaved();
				}
				finally
				{
					writeUnlock();
				}

				parseBufferLocalProperties();

				if(!getPath().equals(oldPath))
				{
					jEdit.updatePosition(oldSymlinkPath,this);
					setMode();
				}
				else
				{
					// if user adds mode buffer-local property
					String newMode = getStringProperty("mode");
					if(newMode != null &&
						!newMode.equals(getMode()
						.getName()))
						setMode();
					else
						propertiesChanged();
				}

				EditBus.send(new BufferUpdate(this,
					view,BufferUpdate.DIRTY_CHANGED));

				// new message type introduced in 4.0pre4
				EditBus.send(new BufferUpdate(this,
					view,BufferUpdate.SAVED));
			}
		} //}}}
	} //}}}

	//}}}
}
