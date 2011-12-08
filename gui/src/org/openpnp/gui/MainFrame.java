/*
 	Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 	
 	This file is part of OpenPnP.
 	
	OpenPnP is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    OpenPnP is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with OpenPnP.  If not, see <http://www.gnu.org/licenses/>.
 	
 	For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FilenameFilter;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.border.BevelBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;

import org.openpnp.BoardLocation;
import org.openpnp.Configuration;
import org.openpnp.Job;
import org.openpnp.JobProcessor;
import org.openpnp.JobProcessor.JobError;
import org.openpnp.JobProcessor.JobState;
import org.openpnp.JobProcessor.PickRetryAction;
import org.openpnp.JobProcessorDelegate;
import org.openpnp.JobProcessorListener;
import org.openpnp.Part;
import org.openpnp.Placement;
import org.openpnp.gui.components.CameraPanel;
import org.openpnp.gui.components.MachineControlsPanel;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.gui.support.OSXAdapter;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Head;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;

/**
 * The main window of the application. Implements the top level menu, Job run
 * and Job setup.
 */
@SuppressWarnings("serial")
public class MainFrame extends JFrame implements JobProcessorListener,
		JobProcessorDelegate, MachineListener, WizardContainer {

	// TODO: consider moving each tab into it's own class
	
	/*
	 * TODO define accelerators and mnemonics
	 * openJobMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O,
	 * Toolkit.getDefaultToolkit().getMenuShortcutKeyMask()));
	 */
	private JobProcessor jobProcessor;
	private BoardsTableModel boardsTableModel;
	private PlacementsTableModel placementsTableModel;
	private JTable boardsTable;
	private JTable placementsTable;
	private Configuration configuration;
	private Machine machine;
	
	private JPanel contentPane;
	private MachineControlsPanel machineControlsPanel;
	private CameraPanel cameraPanel;
	private JLabel lblStatus;
	private JTabbedPane panelBottom;
	

	public MainFrame() {
		createUi();
		
		// Get handlers for quit and close in place
		registerForMacOSXEvents();
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(WindowEvent e) {
				quit();
			}
		});
		

		try {
			Configuration.get().load("config");
		}
		catch (Exception e) {
			// TODO: dialog
			throw new Error(e);
		}
		
		configuration = Configuration.get();
		machine = configuration.getMachine();
		
		try {
			machine.start();
		}
		catch (Exception e) {
			// TODO: dialog
			throw new Error(e);
		}
		
		for (Camera camera : machine.getCameras()) {
			cameraPanel.addCamera(camera);
		}

		boardsTableModel = new BoardsTableModel();
		placementsTableModel = new PlacementsTableModel();

		boardsTable.setModel(boardsTableModel);
		placementsTable.setModel(placementsTableModel);
		
		JPanel panelParts = new JPanel();
		panelBottom.addTab("Parts", null, panelParts, null);

		JPanel panelFeeders = new JPanel();
		panelBottom.addTab("Feeders", null, panelFeeders, null);
		
		jobProcessor = new JobProcessor(configuration);
		jobProcessor.addListener(this);
		jobProcessor.setDelegate(this);
		machine.addListener(this);

		machineControlsPanel.setMachine(machine);

		updateJobControls();
	}

	public void registerForMacOSXEvents() {
		if ((System.getProperty("os.name").toLowerCase().startsWith("mac os x"))) {
			try {
				// Generate and register the OSXAdapter, passing it a hash of
				// all the methods we wish to
				// use as delegates for various
				// com.apple.eawt.ApplicationListener methods
				OSXAdapter.setQuitHandler(this,
						getClass().getDeclaredMethod("quit", (Class[]) null));
//				OSXAdapter.setAboutHandler(this,
//						getClass().getDeclaredMethod("about", (Class[]) null));
//				OSXAdapter.setPreferencesHandler(this, getClass()
//						.getDeclaredMethod("preferences", (Class[]) null));
//				OSXAdapter.setFileHandler(
//						this,
//						getClass().getDeclaredMethod("loadImageFile",
//								new Class[] { String.class }));
			}
			catch (Exception e) {
				System.err.println("Error while loading the OSXAdapter:");
				e.printStackTrace();
			}
		}
	}
	
	public boolean quit() {
		// Attempt to stop the machine on quit
		try {
			machine.setEnabled(false);
		}
		catch (Exception e) {
		}
		System.exit(0);
		return true;
	}

	/**
	 * Updates the Job controls based on the Job state and the Machine's
	 * readiness.
	 */
	private void updateJobControls() {
		JobState state = jobProcessor.getState();
		if (state == JobState.Stopped) {
			startPauseResumeJobAction.setEnabled(true);
			startPauseResumeJobAction.putValue(AbstractAction.NAME, "Start");
			stopJobAction.setEnabled(false);
			stepJobAction.setEnabled(true);
		}
		else if (state == JobState.Running) {
			startPauseResumeJobAction.setEnabled(true);
			startPauseResumeJobAction.putValue(AbstractAction.NAME, "Pause");
			stopJobAction.setEnabled(true);
			stepJobAction.setEnabled(false);
		}
		else if (state == JobState.Paused) {
			startPauseResumeJobAction.setEnabled(true);
			startPauseResumeJobAction.putValue(AbstractAction.NAME, "Resume");
			stopJobAction.setEnabled(true);
			stepJobAction.setEnabled(true);
		}

		// We allow the above to run first so that all state is represented
		// correctly
		// even if the machine is disabled.
		if (!machine.isEnabled()) {
			startPauseResumeJobAction.setEnabled(false);
			stopJobAction.setEnabled(false);
			stepJobAction.setEnabled(false);
		}
	}

	@Override
	public void jobStateChanged(JobState state) {
		updateJobControls();
	}
	
	private void orientBoard() {
		// Get the currently selected board
		int selectedRow = boardsTable.getSelectedRow();
		BoardLocation boardLocation = jobProcessor.getJob().getBoardLocations().get(selectedRow);
		Wizard wizard = new OrientBoardWizard(boardLocation, configuration);
		startWizard(wizard);
	}
	
	private void startWizard(Wizard wizard) {
		// TODO: If there is already a wizard running, take care of that
		
		// Configure the wizard
		wizard.setWizardContainer(this);
		
		// Create a titled panel to hold the wizard
		JPanel panel = new JPanel();
		panel.setBorder(BorderFactory.createTitledBorder("Wizard: " + wizard.getWizardName()));
		panel.setLayout(new BorderLayout());
		panel.add(wizard.getWizardPanel());
		panelBottom.add(panel, "Wizard");
		// TODO: broken
//		panelBottomCardLayout.show(panelBottom, "Wizard");
	}
	
	public void wizardCompleted(Wizard wizard) {
		
	}
	
	public void wizardCancelled(Wizard wizard) {
		
	}

	private void openJob() {
		FileDialog fileDialog = new FileDialog(MainFrame.this);
		fileDialog.setFilenameFilter(new FilenameFilter() {
			@Override
			public boolean accept(File dir, String name) {
				return name.toLowerCase().endsWith(".job.xml");
			}
		});
		fileDialog.setVisible(true);
		try {
			File file = new File(new File(fileDialog.getDirectory()),
					fileDialog.getFile());
			Job job = configuration.loadJob(file);
			jobProcessor.load(job);
		}
		catch (Exception e) {
			e.printStackTrace();
			MessageBoxes.errorBox(this, "Job Load Error", e.getMessage());
		}
	}

	private void startPauseResumeJob() {
		JobState state = jobProcessor.getState();
		if (state == JobState.Stopped) {
			try {
				jobProcessor.start();
			}
			catch (Exception e) {
				MessageBoxes.errorBox(this, "Job Start Error", e.getMessage());
			}
		}
		else if (state == JobState.Paused) {
			jobProcessor.resume();
		}
		else if (state == JobState.Running) {
			jobProcessor.pause();
		}
	}

	private void stepJob() {
		try {
			jobProcessor.step();
		}
		catch (Exception e) {
			MessageBoxes.errorBox(this, "Job Start Error", e.getMessage());
		}
	}

	private void stopJob() {
		jobProcessor.stop();
	}

	@Override
	public void jobLoaded(Job job) {
		placementsTableModel.setPlacements(null);
		boardsTableModel.setJob(jobProcessor.getJob());
		updateJobControls();
	}

	@Override
	public PickRetryAction partPickFailed(BoardLocation board, Part part,
			Feeder feeder) {
		return PickRetryAction.SkipAndContinue;
	}

	@Override
	public void jobEncounteredError(JobError error, String description) {
		MessageBoxes.errorBox(this, error.toString(), description);
	}

	@Override
	public void boardProcessingCompleted(BoardLocation board) {
	}

	@Override
	public void boardProcessingStarted(BoardLocation board) {
	}

	@Override
	public void partPicked(BoardLocation board, Placement placement) {
	}

	@Override
	public void partPlaced(BoardLocation board, Placement placement) {
	}

	@Override
	public void partProcessingCompleted(BoardLocation board, Placement placement) {
		// partsComplete++;
	}

	@Override
	public void partProcessingStarted(BoardLocation board, Placement placement) {
	}

	@Override
	public void detailedStatusUpdated(String status) {
		lblStatus.setText(status);
	}

	@Override
	public void machineHeadActivity(Machine machine, Head head) {
	}

	@Override
	public void machineEnabled(Machine machine) {
		updateJobControls();
	}

	@Override
	public void machineEnableFailed(Machine machine, String reason) {
	}

	@Override
	public void machineDisabled(Machine machine, String reason) {
		updateJobControls();
		jobProcessor.stop();
	}

	@Override
	public void machineDisableFailed(Machine machine, String reason) {
	}
	
	private void createUi() {
		setBounds(100, 100, 1280, 1024);

		JMenuBar menuBar = new JMenuBar();
		setJMenuBar(menuBar);

		JMenu mnFile = new JMenu("File");
		menuBar.add(mnFile);

		JMenuItem mntmNew = new JMenuItem("New");
		mntmNew.setAction(newJobAction);
		mnFile.add(mntmNew);

		JMenuItem mntmOpen = new JMenuItem("Open");
		mntmOpen.setAction(openJobAction);
		mnFile.add(mntmOpen);

		mnFile.addSeparator();

		JMenuItem mntmClose = new JMenuItem("Close");
		mntmClose.setAction(closeJobAction);
		mnFile.add(mntmClose);

		mnFile.addSeparator();

		JMenuItem mntmSave = new JMenuItem("Save");
		mntmSave.setAction(saveJobAction);
		mnFile.add(mntmSave);

		JMenuItem mntmSaveAs = new JMenuItem("Save As");
		mntmSaveAs.setAction(saveJobAsAction);
		mnFile.add(mntmSaveAs);

		JMenu mnEdit = new JMenu("Edit");
		menuBar.add(mnEdit);

		JMenuItem mntmAddBoard = new JMenuItem("Add Board");
		mntmAddBoard.setAction(addBoardAction);
		mnEdit.add(mntmAddBoard);

		JMenuItem mntmDeleteBoard = new JMenuItem("Delete Board");
		mntmDeleteBoard.setAction(deleteBoardAction);
		mnEdit.add(mntmDeleteBoard);

		JMenuItem mntmEnableBoard = new JMenuItem("Enable Board");
		mntmEnableBoard.setAction(enableDisableBoardAction);
		mnEdit.add(mntmEnableBoard);

		mnEdit.addSeparator();

		JMenuItem mntmMoveBoardUp = new JMenuItem("Move Board Up");
		mntmMoveBoardUp.setAction(moveBoardUpAction);
		mnEdit.add(mntmMoveBoardUp);

		JMenuItem mntmMoveBoardDown = new JMenuItem("Move Board Down");
		mntmMoveBoardDown.setAction(moveBoardDownAction);
		mnEdit.add(mntmMoveBoardDown);

		mnEdit.addSeparator();

		JMenuItem mntmSetBoardLocation = new JMenuItem("Set Board Location");
		mntmSetBoardLocation.setAction(orientBoardAction);
		mnEdit.add(mntmSetBoardLocation);

		JMenu mnJob = new JMenu("Job Control");
		menuBar.add(mnJob);

		JMenuItem mntmNewMenuItem = new JMenuItem("Start Job");
		mntmNewMenuItem.setAction(startPauseResumeJobAction);
		mnJob.add(mntmNewMenuItem);

		JMenuItem mntmStepJob = new JMenuItem("Step Job");
		mntmStepJob.setAction(stepJobAction);
		mnJob.add(mntmStepJob);

		JMenuItem mntmStopJob = new JMenuItem("Stop Job");
		mntmStopJob.setAction(stopJobAction);
		mnJob.add(mntmStopJob);

		contentPane = new JPanel();
		contentPane.setBorder(new EmptyBorder(5, 5, 5, 5));
		setContentPane(contentPane);
		contentPane.setLayout(new BorderLayout(0, 0));
		
		JSplitPane splitPaneTopBottom = new JSplitPane();
		splitPaneTopBottom.setBorder(null);
		splitPaneTopBottom.setOrientation(JSplitPane.VERTICAL_SPLIT);
		splitPaneTopBottom.setContinuousLayout(true);
		contentPane.add(splitPaneTopBottom, BorderLayout.CENTER);
		
		JPanel panelTop = new JPanel();
		splitPaneTopBottom.setLeftComponent(panelTop);
		panelTop.setLayout(new BorderLayout(0, 0));
		
				JPanel panelLeftColumn = new JPanel();
				panelTop.add(panelLeftColumn, BorderLayout.WEST);
				FlowLayout flowLayout = (FlowLayout) panelLeftColumn.getLayout();
				flowLayout.setVgap(0);
				flowLayout.setHgap(0);
				
						JPanel panel = new JPanel();
						panelLeftColumn.add(panel);
						panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
						
								machineControlsPanel = new MachineControlsPanel();
								machineControlsPanel.setBorder(new TitledBorder(null,
										"Machine Controls", TitledBorder.LEADING, TitledBorder.TOP,
										null, null));
								panel.add(machineControlsPanel);
								
										cameraPanel = new CameraPanel();
										panelTop.add(cameraPanel, BorderLayout.CENTER);
										cameraPanel.setBorder(new TitledBorder(null, "Cameras",
												TitledBorder.LEADING, TitledBorder.TOP, null, null));
		
		panelBottom = new JTabbedPane(JTabbedPane.TOP);
		splitPaneTopBottom.setRightComponent(panelBottom);

		JPanel panelJob = new JPanel();
		panelBottom.addTab("Job", panelJob);
		panelJob.setLayout(new BorderLayout(0, 0));

		placementsTable = new JTable();
		JScrollPane partsTableScroller = new JScrollPane(placementsTable);

		boardsTable = new JTable();
		JScrollPane boardsTableScroller = new JScrollPane(boardsTable);
		boardsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		
		boardsTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
			@Override
			public void valueChanged(ListSelectionEvent e) {
				List<Placement> placements = jobProcessor.getJob().getBoardLocations().get(e.getFirstIndex()).getBoard().getPlacements();
				placementsTableModel.setPlacements(placements);
			}
		});
		

		JPanel panelRight = new JPanel();
		panelRight.setLayout(new BorderLayout());
		panelRight.add(partsTableScroller);

		JPanel panelLeft = new JPanel();
		panelLeft.setLayout(new BorderLayout());
		
		JPanel panelJobControl = new JPanel();
		panelLeft.add(panelJobControl, BorderLayout.WEST);
		panelJobControl.setLayout(new BoxLayout(panelJobControl, BoxLayout.Y_AXIS));
		
		JButton btnStart = new JButton("Start");
		btnStart.setAction(startPauseResumeJobAction);
		btnStart.setPreferredSize(new Dimension(80, 80));
		btnStart.setFocusable(false);
		panelJobControl.add(btnStart);
		
		JButton btnStep = new JButton(stepJobAction);
		btnStep.setPreferredSize(new Dimension(80, 80));
		btnStep.setFocusable(false);
		panelJobControl.add(btnStep);
		
		JButton btnStop = new JButton("Stop");
		btnStop.setAction(stopJobAction);
		btnStop.setPreferredSize(new Dimension(80, 80));
		btnStop.setFocusable(false);
		panelJobControl.add(btnStop);
		
		Component glue = Box.createGlue();
		panelJobControl.add(glue);
		panelLeft.add(boardsTableScroller);

		JSplitPane splitPaneLeftRight = new JSplitPane();
		splitPaneLeftRight.setBorder(null);
		panelJob.add(splitPaneLeftRight);
		splitPaneLeftRight.setContinuousLayout(true);
		splitPaneLeftRight.setDividerLocation(350);
		splitPaneLeftRight.setLeftComponent(panelLeft);
		splitPaneLeftRight.setRightComponent(panelRight);
		splitPaneTopBottom.setDividerLocation(700);
		
		lblStatus = new JLabel(" ");
		lblStatus.setBorder(new BevelBorder(BevelBorder.LOWERED, null, null, null, null));
		contentPane.add(lblStatus, BorderLayout.SOUTH);
	}
	
	private Action stopJobAction = new AbstractAction("Stop") {
		@Override
		public void actionPerformed(ActionEvent arg0) {
			stopJob();
		}
	};

	private Action startPauseResumeJobAction = new AbstractAction("Start") {
		@Override
		public void actionPerformed(ActionEvent arg0) {
			startPauseResumeJob();
		}
	};

	private Action stepJobAction = new AbstractAction("Step") {
		@Override
		public void actionPerformed(ActionEvent arg0) {
			stepJob();
		}
	};

	private Action openJobAction = new AbstractAction("Open Job...") {
		@Override
		public void actionPerformed(ActionEvent arg0) {
			openJob();
		}
	};

	private Action closeJobAction = new AbstractAction("Close Job") {
		@Override
		public void actionPerformed(ActionEvent arg0) {
		}
	};

	private Action newJobAction = new AbstractAction("New Job") {
		@Override
		public void actionPerformed(ActionEvent arg0) {
		}
	};

	private Action saveJobAction = new AbstractAction("Save Job") {
		@Override
		public void actionPerformed(ActionEvent arg0) {
		}
	};

	private Action saveJobAsAction = new AbstractAction("Save Job As...") {
		@Override
		public void actionPerformed(ActionEvent arg0) {
		}
	};

	private Action addBoardAction = new AbstractAction("Add Board...") {
		@Override
		public void actionPerformed(ActionEvent arg0) {
		}
	};

	private Action moveBoardUpAction = new AbstractAction("Move Board Up") {
		@Override
		public void actionPerformed(ActionEvent arg0) {
		}
	};

	private Action moveBoardDownAction = new AbstractAction("Move Board Down") {
		@Override
		public void actionPerformed(ActionEvent arg0) {
		}
	};

	private Action orientBoardAction = new AbstractAction("Set Board Location") {
		@Override
		public void actionPerformed(ActionEvent arg0) {
			orientBoard();
		}
	};

	private Action deleteBoardAction = new AbstractAction("Delete Board") {
		@Override
		public void actionPerformed(ActionEvent arg0) {
		}
	};

	private Action enableDisableBoardAction = new AbstractAction("Enable Board") {
		@Override
		public void actionPerformed(ActionEvent arg0) {
		}
	};
}
