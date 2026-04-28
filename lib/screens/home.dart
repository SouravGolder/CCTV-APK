import 'dart:io';
import 'package:flutter/material.dart';
import 'package:open_file/open_file.dart';
import 'package:file_picker/file_picker.dart';
import 'package:permission_handler/permission_handler.dart';
import '../services/ffmpeg_service.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final rtsp = TextEditingController();
  final segment = TextEditingController();
  final filterDate = TextEditingController();
  final filterHour = TextEditingController();
  final savePath = TextEditingController();
  bool recording = false;
  List<FileSystemEntity> recordings = [];
  List<FileSystemEntity> filteredRecordings = [];
  bool isFilterActive = false;

  @override
  void initState() {
    super.initState();
    segment.text = '60';
    FFmpegService.init().then((_) {
      if (mounted) setState(() {});
    });
    refreshRecordings();
  }

  void start() async {
    final url = rtsp.text.trim();
    final time = int.tryParse(segment.text) ?? 60;

    if (url.isEmpty) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Please enter RTSP URL')));
      return;
    }

    final messenger = ScaffoldMessenger.of(context);
    try {
      await FFmpegService.ensureStoragePermission();
      await FFmpegService.start(url, time);
      if (!mounted) return;
      setState(() => recording = true);
    } catch (e) {
      if (!mounted) return;
      await _maybeOpenPermissionSettings(e.toString());
      messenger.showSnackBar(SnackBar(content: Text('Error: $e')));
    }
  }

  void stop() async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      await FFmpegService.stop();
      if (!mounted) return;
      setState(() => recording = false);
      await Future.delayed(
        Duration(milliseconds: 500),
      ); // Give file time to flush
      refreshRecordings();
      messenger.showSnackBar(
        SnackBar(
          content: Text('✓ Recording saved successfully'),
          duration: Duration(seconds: 2),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      setState(() => recording = false);
      messenger.showSnackBar(
        SnackBar(
          content: Text('✗ Recording failed: $e'),
          backgroundColor: Colors.red,
          duration: Duration(seconds: 4),
        ),
      );
    }
  }

  void refreshRecordings() async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      final files = await FFmpegService.getRecordings();
      if (!mounted) return;
      setState(() => recordings = files);
    } catch (e) {
      if (!mounted) return;
      await _maybeOpenPermissionSettings(e.toString());
      messenger.showSnackBar(
        SnackBar(content: Text('Error loading recordings: $e')),
      );
    }
  }

  Future<void> _maybeOpenPermissionSettings(String error) async {
    if (!mounted) return;
    if (!error.toLowerCase().contains('permission')) return;
    final go = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Storage permission required'),
        content: Text(
          'To save recordings to your selected folder, please grant storage permission in system settings.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text('Open settings'),
          ),
        ],
      ),
    );
    if (go == true) {
      await openAppSettings();
    }
  }

  void openCCTVFolder() async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      await FFmpegService.openCCTVFolder();
      if (!mounted) return;
      messenger.showSnackBar(
        SnackBar(
          content: Text('📁 Opening folder...'),
          duration: Duration(seconds: 2),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      await _maybeOpenPermissionSettings(e.toString());
      messenger.showSnackBar(
        SnackBar(content: Text('Error: $e'), backgroundColor: Colors.red),
      );
    }
  }

  void playRecording(String filePath) async {
    final messenger = ScaffoldMessenger.of(context);
    try {
      final result = await OpenFile.open(filePath);
      if (!mounted) return;
      if (result.type == ResultType.error) {
        messenger.showSnackBar(
          SnackBar(
            content: Text('Cannot open video: ${result.message}'),
            backgroundColor: Colors.red,
            duration: Duration(seconds: 3),
          ),
        );
      } else {
        messenger.showSnackBar(
          SnackBar(
            content: Text('Opening video...'),
            duration: Duration(seconds: 1),
          ),
        );
      }
    } catch (e) {
      if (!mounted) return;
      messenger.showSnackBar(
        SnackBar(
          content: Text('Error opening video: $e'),
          backgroundColor: Colors.red,
        ),
      );
    }
  }

  void deleteRecording(FileSystemEntity file) async {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Delete Recording?'),
        content: Text('This action cannot be undone.'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text('Cancel'),
          ),
          TextButton(
            onPressed: () async {
              final messenger = ScaffoldMessenger.of(context);
              try {
                await FFmpegService.deleteRecording(file.path);
                refreshRecordings();
                Navigator.pop(context);
                messenger.showSnackBar(SnackBar(content: Text('✓ Deleted')));
              } catch (e) {
                Navigator.pop(context);
                messenger.showSnackBar(SnackBar(content: Text('Error: $e')));
              }
            },
            child: Text('Delete', style: TextStyle(color: Colors.red)),
          ),
        ],
      ),
    );
  }

  void applyFilter() {
    final dateStr = filterDate.text.trim();
    final hourStr = filterHour.text.trim();

    if (dateStr.isEmpty || hourStr.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Please enter both date (YYYY/MM/DD) and hour'),
          backgroundColor: Colors.orange,
        ),
      );
      return;
    }

    try {
      // Parse date: convert "2026/04/22" to "20260422"
      final dateParts = dateStr.split('/');
      if (dateParts.length != 3) {
        throw Exception('Invalid date format. Use YYYY/MM/DD');
      }
      final formattedDate =
          '${dateParts[0]}${dateParts[1].padLeft(2, '0')}${dateParts[2].padLeft(2, '0')}';

      // Parse hour: validate it's between 0-23
      final hour = int.parse(hourStr);
      if (hour < 0 || hour > 23) {
        throw Exception('Hour must be between 0 and 23');
      }
      final formattedHour = hour.toString().padLeft(2, '0');

      // Filter recordings
      filteredRecordings = recordings.where((file) {
        final fileName = file.path.split('/').last;
        // Extract date and hour from filename: recording_20260422_143022.mp4
        if (fileName.length < 21) return false;

        final fileDate = fileName.substring(10, 18);
        final fileHour = fileName.substring(19, 21);

        return fileDate == formattedDate && fileHour == formattedHour;
      }).toList();

      setState(() {
        isFilterActive = true;
      });

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            '✓ Filter applied: ${filteredRecordings.length} video(s) found',
          ),
          duration: Duration(seconds: 2),
        ),
      );
    } catch (e) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Error: $e'), backgroundColor: Colors.red),
      );
    }
  }

  void clearFilter() {
    setState(() {
      isFilterActive = false;
      filteredRecordings = [];
      filterDate.clear();
      filterHour.clear();
    });
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('✓ Filter cleared'),
        duration: Duration(seconds: 1),
      ),
    );
  }

  @override
  void dispose() {
    rtsp.dispose();
    segment.dispose();
    filterDate.dispose();
    filterHour.dispose();
    savePath.dispose();
    super.dispose();
  }

  Future<void> configureSaveFolder() async {
    savePath.text = FFmpegService.getCCTVFolderPath();

    await showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Recording Save Folder'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: savePath,
              decoration: InputDecoration(
                labelText: 'Folder path',
                hintText: '/storage/emulated/0/Download/CCTV_Recordings',
                border: OutlineInputBorder(),
              ),
            ),
            SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: () async {
                      final selected =
                          await FilePicker.platform.getDirectoryPath();
                      if (selected != null && selected.trim().isNotEmpty) {
                        savePath.text = selected.trim();
                      }
                    },
                    icon: Icon(Icons.folder),
                    label: Text('Pick'),
                  ),
                ),
                SizedBox(width: 8),
                Expanded(
                  child: OutlinedButton(
                    onPressed: () async {
                      await FFmpegService.setCustomFolderPath(null);
                      if (!context.mounted) return;
                      Navigator.pop(context);
                      setState(() {});
                      refreshRecordings();
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(content: Text('✓ Reset to default folder')),
                      );
                    },
                    child: Text('Reset'),
                  ),
                ),
              ],
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text('Cancel'),
          ),
          ElevatedButton(
            onPressed: () async {
              try {
                await FFmpegService.ensureStoragePermission();
                await FFmpegService.setCustomFolderPath(savePath.text);
                if (!context.mounted) return;
                Navigator.pop(context);
                setState(() {});
                refreshRecordings();
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(content: Text('✓ Save folder updated')),
                );
              } catch (e) {
                if (!context.mounted) return;
                await _maybeOpenPermissionSettings(e.toString());
                ScaffoldMessenger.of(context).showSnackBar(
                  SnackBar(
                    content: Text('Error: $e'),
                    backgroundColor: Colors.red,
                  ),
                );
              }
            },
            child: Text('Save'),
          ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text("CCTV Recorder"),
            Text(
              FFmpegService.getCCTVFolderPath(),
              style: TextStyle(fontSize: 11, fontWeight: FontWeight.normal),
            ),
          ],
        ),
        actions: [
          IconButton(
            icon: Icon(Icons.settings),
            tooltip: 'Save Folder',
            onPressed: recording ? null : configureSaveFolder,
          ),
          IconButton(
            icon: Icon(Icons.folder_open),
            tooltip: 'Open CCTV Folder',
            onPressed: openCCTVFolder,
          ),
          IconButton(
            icon: Icon(Icons.refresh),
            tooltip: 'Refresh',
            onPressed: refreshRecordings,
          ),
        ],
      ),
      body: SingleChildScrollView(
        child: Padding(
          padding: EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Input Section
              Card(
                child: Padding(
                  padding: EdgeInsets.all(16),
                  child: Column(
                    children: [
                      TextField(
                        controller: rtsp,
                        decoration: InputDecoration(
                          labelText: "RTSP URL",
                          hintText: "rtsp://camera-ip/stream",
                          prefixIcon: Icon(Icons.videocam),
                          border: OutlineInputBorder(),
                        ),
                        enabled: !recording,
                      ),
                      SizedBox(height: 12),
                      TextField(
                        controller: segment,
                        decoration: InputDecoration(
                          labelText: "Segment Time (seconds)",
                          prefixIcon: Icon(Icons.timer),
                          border: OutlineInputBorder(),
                        ),
                        keyboardType: TextInputType.number,
                        enabled: !recording,
                      ),
                      SizedBox(height: 16),
                      SizedBox(
                        width: double.infinity,
                        child: ElevatedButton.icon(
                          onPressed: recording ? stop : start,
                          icon: Icon(
                            recording ? Icons.stop_circle : Icons.play_arrow,
                          ),
                          label: Text(
                            recording
                                ? '🔴 Stop Recording'
                                : '▶️ Start Recording',
                            style: TextStyle(fontSize: 16),
                          ),
                          style: ElevatedButton.styleFrom(
                            padding: EdgeInsets.symmetric(vertical: 12),
                            backgroundColor: recording
                                ? Colors.red
                                : Colors.green,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
              SizedBox(height: 24),

              // Recordings Section
              Text(
                'Saved Recordings (${recordings.length})',
                style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
              ),
              SizedBox(height: 12),

              // Filter Section
              Card(
                child: Padding(
                  padding: EdgeInsets.all(12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Filter Videos',
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      SizedBox(height: 12),
                      Row(
                        children: [
                          Expanded(
                            child: TextField(
                              controller: filterDate,
                              decoration: InputDecoration(
                                labelText: 'Date (YYYY/MM/DD)',
                                hintText: '2026/04/22',
                                prefixIcon: Icon(Icons.calendar_today),
                                border: OutlineInputBorder(),
                                contentPadding: EdgeInsets.symmetric(
                                  horizontal: 12,
                                  vertical: 12,
                                ),
                              ),
                            ),
                          ),
                          SizedBox(width: 12),
                          Expanded(
                            child: TextField(
                              controller: filterHour,
                              decoration: InputDecoration(
                                labelText: 'Hour (0-23)',
                                hintText: '1',
                                prefixIcon: Icon(Icons.schedule),
                                border: OutlineInputBorder(),
                                contentPadding: EdgeInsets.symmetric(
                                  horizontal: 12,
                                  vertical: 12,
                                ),
                              ),
                              keyboardType: TextInputType.number,
                            ),
                          ),
                        ],
                      ),
                      SizedBox(height: 12),
                      Row(
                        children: [
                          Expanded(
                            child: ElevatedButton.icon(
                              onPressed: applyFilter,
                              icon: Icon(Icons.search),
                              label: Text('Apply Filter'),
                              style: ElevatedButton.styleFrom(
                                backgroundColor: Colors.blue,
                                padding: EdgeInsets.symmetric(vertical: 12),
                              ),
                            ),
                          ),
                          SizedBox(width: 8),
                          ElevatedButton.icon(
                            onPressed: clearFilter,
                            icon: Icon(Icons.clear),
                            label: Text('Clear'),
                            style: ElevatedButton.styleFrom(
                              backgroundColor: Colors.grey,
                              padding: EdgeInsets.symmetric(vertical: 12),
                            ),
                          ),
                        ],
                      ),
                      if (isFilterActive)
                        Padding(
                          padding: EdgeInsets.only(top: 12),
                          child: Text(
                            '✓ Filter active: ${filteredRecordings.length} video(s) shown',
                            style: TextStyle(
                              color: Colors.green,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
              SizedBox(height: 12),

              // Display filtered or all recordings
              if ((isFilterActive ? filteredRecordings : recordings).isEmpty)
                Card(
                  child: Padding(
                    padding: EdgeInsets.all(32),
                    child: Center(
                      child: Column(
                        children: [
                          Icon(
                            Icons.videocam_off,
                            size: 48,
                            color: Colors.grey,
                          ),
                          SizedBox(height: 12),
                          Text(
                            isFilterActive
                                ? 'No videos found for this filter'
                                : 'No recordings yet',
                            style: TextStyle(color: Colors.grey, fontSize: 16),
                          ),
                        ],
                      ),
                    ),
                  ),
                )
              else
                ListView.builder(
                  shrinkWrap: true,
                  physics: NeverScrollableScrollPhysics(),
                  itemCount: isFilterActive
                      ? filteredRecordings.length
                      : recordings.length,
                  itemBuilder: (context, index) {
                    final file = isFilterActive
                        ? filteredRecordings[index]
                        : recordings[index];
                    final stat = file.statSync();
                    final fileName = file.path.split('/').last;
                    final fileSize = FFmpegService.getFormattedFileSize(
                      stat.size,
                    );
                    final modifiedTime = stat.modified
                        .toString()
                        .split('.')
                        .first;

                    return Card(
                      child: ListTile(
                        leading: Icon(Icons.videocam, color: Colors.blue),
                        title: Text(fileName),
                        subtitle: Text('$fileSize • $modifiedTime'),
                        onTap: () => playRecording(file.path),
                        trailing: IconButton(
                          icon: Icon(Icons.delete, color: Colors.red),
                          onPressed: () => deleteRecording(file),
                        ),
                      ),
                    );
                  },
                ),
            ],
          ),
        ),
      ),
    );
  }
}
