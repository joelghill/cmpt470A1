/*
 * SplashScreen.java - Splash screen
 * Copyright (C) 1998, 2004 Slava Pestov
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

package org.gjt.sp.jedit.gui;

import javax.swing.*;

import java.awt.*;
import java.util.StringTokenizer;
import java.util.Vector;

import org.gjt.sp.jedit.jEdit;
import org.gjt.sp.jedit.gui.AboutDialog.AboutPanel.AnimationThread;
import org.gjt.sp.util.Log;

/**
 * The splash screen displayed on startup.
 * @version $Id: SplashScreen.java 8122 2006-11-24 11:29:49Z kpouer $
 */
public class SplashScreen extends JComponent
{
	
	public SplashScreen()
	{
		String ncidJordan = "jcl175";
		String emailJordan = "jcl175@mail.usask.ca";
		String ncidJoel = "jgh719";
		String emailJoel = "jgh719@mail.usask.ca";
		
		setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
		setBackground(Color.white);

		Font font = new Font("Dialog",Font.PLAIN,10);
		setFont(font);
		fm = getFontMetrics(font);
		
		
		/*
		 * Set scrolling text
		  */
		
		text = new Vector(50);
		text.addElement(ncidJordan);
		text.addElement(emailJordan);
		text.addElement(ncidJoel);
		text.addElement(emailJoel);
		maxWidth = fm.stringWidth(emailJordan) + 200;
		scrollPosition = -250;

		thread = new AnimationThread();

		image = getToolkit().getImage(
			getClass().getResource("/org/gjt/sp/jedit/icons/splash.png"));
		MediaTracker tracker = new MediaTracker(this);
		tracker.addImage(image,0);

		try
		{
			tracker.waitForAll();
		}
		catch(Exception e)
		{
			Log.log(Log.ERROR,this,e);
		}

		win = new JWindow();
		GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
		GraphicsDevice[] gs = ge.getScreenDevices();
		int height = gs[0].getDisplayMode().getHeight();
		int width = gs[0].getDisplayMode().getWidth();
		Dimension screen = new Dimension(width, height);
		Dimension size = new Dimension(image.getWidth(this) + 2,
			image.getHeight(this) + 2 + PROGRESS_HEIGHT);
		win.setSize(size);

		win.getContentPane().add(this, BorderLayout.CENTER);

		win.setLocation((screen.width - size.width) / 2,
			(screen.height - size.height) / 2);
		win.validate();
		win.setVisible(true);
	}

	public void dispose()
	{
		win.dispose();
	}

	public synchronized void advance()
	{
		logAdvanceTime(null);
		progress++;
		repaint();

		// wait for it to be painted to ensure progress is updated
		// continuously
		try
		{
			wait();
		}
		catch(InterruptedException ie)
		{
			Log.log(Log.ERROR,this,ie);
		}
	}

	public synchronized void advance(String label)
	{
		logAdvanceTime(label);
		progress++;
		this.label = label;
		repaint();

		// wait for it to be painted to ensure progress is updated
		// continuously
		try
		{
			wait();
		}
		catch(InterruptedException ie)
		{
			Log.log(Log.ERROR,this,ie);
		}
	}

	private void logAdvanceTime(String label)
	{
		long currentTime = System.currentTimeMillis();
		if (lastLabel != null)
		{
			Log.log(Log.DEBUG, SplashScreen.class, lastLabel +':'+(currentTime - lastAdvanceTime) + "ms");
		}
		if (label != null)
		{
			lastLabel = label;
			lastAdvanceTime = currentTime;

		}

	}

	public synchronized void paintComponent(Graphics g)
	{
		
		Dimension size = getSize();

		g.setColor(Color.black);
		g.drawRect(0,0,size.width - 1,size.height - 1);

		g.drawImage(image,1,1,this);

		// XXX: This should not be hardcoded
		g.setColor(Color.white);
		g.fillRect(1,image.getHeight(this) + 1,
			((win.getWidth() - 2) * progress) / PROGRESS_COUNT, PROGRESS_HEIGHT);

		g.setColor(Color.black);

		if (label != null)
		{
			g.drawString(label,
				     (getWidth() - fm.stringWidth(label)) / 2,
				     image.getHeight(this) + (PROGRESS_HEIGHT
							      + fm.getAscent() + fm.getDescent()) / 2);
		}
		
		
		
	


		String version = jEdit.getVersion();
		g.drawString(version,
			getWidth() - fm.stringWidth(version) - 2,
			image.getHeight(this) - fm.getDescent());
		
		System.out.print(getHeight());
		
		/*
		 * Scrolling text
		 */
		
		int height = fm.getHeight();
		int firstLine = scrollPosition / height;

		int firstLineOffset = height - scrollPosition % height;
		int lines = (getHeight() - TOP - BOTTOM) / height;

		int y = firstLineOffset;
		
		
		for(int i = 0; i <= lines; i++)
		{
			if(i + firstLine >= 0 && i + firstLine < text.size())
			{
				String line = (String)text.get(i + firstLine);
				g.drawString(line,(maxWidth - fm.stringWidth(line))/2,y);
			}
			y += fm.getHeight();
		}
		
		notify();
		
	}
	
	public void addNotify()
	{
		super.addNotify();
		thread.start();
	}

	public void removeNotify()
	{
		super.removeNotify();
		thread.kill();
	}
	
	class AnimationThread extends Thread
	{
		private boolean running = true;
		private long last;

		AnimationThread()
		{
			super("Start Up scrolling text");
			setPriority(Thread.MIN_PRIORITY);
		}
		
		public void kill()
		{
			running = false;
		}

		public void run()
		{
			FontMetrics fm = getFontMetrics(getFont());
			int max = (text.size() * fm.getHeight());

			while (running)
			{
				scrollPosition += 1;

				if(scrollPosition > max)
					scrollPosition = -250;

				if(last != 0)
				{
					long frameDelay =
						System.currentTimeMillis()
						- last;

					try
					{
						Thread.sleep(
							75
							- frameDelay);
					}
					catch(Exception e)
					{
					}
				}

				last = System.currentTimeMillis();

				repaint(getWidth() / 2 - maxWidth,
					TOP,maxWidth * 2,
					getHeight() - TOP - BOTTOM);
			}
		}
	}


	

	// private members
	private final FontMetrics fm;
	private final JWindow win;
	private final Image image;
	private int progress;
	private static final int PROGRESS_HEIGHT = 20;
	private static final int PROGRESS_COUNT = 28;
	private String label;
	private String lastLabel;
	private long lastAdvanceTime = System.currentTimeMillis();
	
	//position and thread for scrolling text
	Vector text;
	int scrollPosition;
	AnimationThread thread;
	public static int TOP = 0;
	public static int BOTTOM = 0;
	int maxWidth;

}
