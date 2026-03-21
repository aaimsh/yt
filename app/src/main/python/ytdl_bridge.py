"""
Bridge module between Android/Kotlin and yt-dlp.
Called from Kotlin via Chaquopy's Python API.
"""

import json
import os
import yt_dlp


def get_video_info(url):
    """Fetch video metadata and available formats without downloading."""
    ydl_opts = {
        'quiet': True,
        'no_warnings': True,
        'skip_download': True,
    }

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)

        formats = []
        seen = set()

        for f in info.get('formats', []):
            fmt_id = f.get('format_id', '')
            ext = f.get('ext', 'mp4')
            height = f.get('height')
            width = f.get('width')
            acodec = f.get('acodec', 'none')
            vcodec = f.get('vcodec', 'none')
            filesize = f.get('filesize') or f.get('filesize_approx')
            has_video = vcodec != 'none'
            has_audio = acodec != 'none'

            if has_video and height:
                res = f"{height}p"
            elif has_audio and not has_video:
                abr = f.get('abr', '')
                res = f"audio {abr}kbps" if abr else "audio"
            else:
                continue

            key = f"{res}_{ext}_{has_video}_{has_audio}"
            if key in seen:
                continue
            seen.add(key)

            desc_parts = [res, ext]
            if has_video and has_audio:
                desc_parts.append("(video+audio)")
            elif has_video:
                desc_parts.append("(video only)")
            elif has_audio:
                desc_parts.append("(audio only)")

            formats.append({
                'format_id': fmt_id,
                'extension': ext,
                'resolution': res,
                'file_size': filesize,
                'description': ' | '.join(desc_parts),
                'has_video': has_video,
                'has_audio': has_audio,
            })

        # Sort: video+audio first, then by resolution descending
        def sort_key(f):
            combo = 0 if (f['has_video'] and f['has_audio']) else 1
            res_num = 0
            r = f['resolution']
            if 'p' in r:
                try:
                    res_num = int(r.replace('p', ''))
                except ValueError:
                    pass
            return (combo, -res_num)

        formats.sort(key=sort_key)

        result = {
            'title': info.get('title', 'Unknown'),
            'thumbnail': info.get('thumbnail'),
            'duration': info.get('duration'),
            'uploader': info.get('uploader'),
            'formats': formats,
        }
        return json.dumps(result)

    except Exception as e:
        return json.dumps({'error': str(e)})


def download_video(url, output_dir, format_id=None, audio_only=False,
                   progress_callback=None):
    """
    Download a video using yt-dlp.

    Args:
        url: Video URL
        output_dir: Directory to save the file
        format_id: Specific format ID, or None for best
        audio_only: If True, download best audio and convert to mp3
        progress_callback: Python callable for progress updates (called from yt-dlp hooks)

    Returns:
        JSON string with result info
    """

    def _progress_hook(d):
        if progress_callback is None:
            return

        status = d.get('status', '')
        if status == 'downloading':
            total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
            downloaded = d.get('downloaded_bytes', 0)
            speed = d.get('speed', 0)
            eta = d.get('eta', 0)

            pct = (downloaded / total * 100) if total > 0 else 0
            speed_str = ""
            if speed:
                if speed > 1024 * 1024:
                    speed_str = f"{speed / 1024 / 1024:.1f} MB/s"
                elif speed > 1024:
                    speed_str = f"{speed / 1024:.1f} KB/s"
                else:
                    speed_str = f"{speed:.0f} B/s"

            eta_str = ""
            if eta:
                mins, secs = divmod(int(eta), 60)
                eta_str = f"{mins}:{secs:02d}" if mins else f"{secs}s"

            progress_callback(json.dumps({
                'status': 'downloading',
                'percent': pct,
                'speed': speed_str,
                'eta': eta_str,
            }))

        elif status == 'finished':
            progress_callback(json.dumps({
                'status': 'merging',
                'percent': 100,
                'speed': '',
                'eta': '',
            }))

    os.makedirs(output_dir, exist_ok=True)

    ydl_opts = {
        'outtmpl': os.path.join(output_dir, '%(title)s.%(ext)s'),
        'progress_hooks': [_progress_hook],
        'quiet': True,
        'no_warnings': True,
        'merge_output_format': 'mp4',
    }

    if audio_only:
        ydl_opts['format'] = 'bestaudio/best'
        ydl_opts['postprocessors'] = [{
            'key': 'FFmpegExtractAudio',
            'preferredcodec': 'mp3',
            'preferredquality': '192',
        }]
    elif format_id:
        # If format is video-only, also get best audio and merge
        ydl_opts['format'] = f"{format_id}+bestaudio/best/{format_id}"
    else:
        ydl_opts['format'] = 'bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best'

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)
            filename = ydl.prepare_filename(info)

            # Adjust extension for audio-only
            if audio_only:
                base, _ = os.path.splitext(filename)
                filename = base + '.mp3'

            return json.dumps({
                'success': True,
                'filename': filename,
                'title': info.get('title', 'Unknown'),
            })

    except Exception as e:
        return json.dumps({
            'success': False,
            'error': str(e),
        })


def get_preset_formats():
    """Return a list of user-friendly preset download options."""
    presets = [
        {
            'id': 'best_video',
            'label': 'Best Quality (Video + Audio)',
            'description': 'Highest available resolution with audio',
            'icon': 'high_quality',
        },
        {
            'id': '1080p',
            'label': '1080p Full HD',
            'description': '1920x1080 resolution',
            'icon': 'hd',
        },
        {
            'id': '720p',
            'label': '720p HD',
            'description': '1280x720 resolution',
            'icon': 'hd',
        },
        {
            'id': '480p',
            'label': '480p SD',
            'description': 'Standard definition, smaller file',
            'icon': 'sd',
        },
        {
            'id': 'audio_only',
            'label': 'Audio Only (MP3)',
            'description': 'Extract audio as MP3 file',
            'icon': 'music_note',
        },
    ]
    return json.dumps(presets)


def download_with_preset(url, output_dir, preset_id, progress_callback=None):
    """Download using a preset quality option."""
    format_map = {
        'best_video': 'bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best',
        '1080p': 'bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080]/best',
        '720p': 'bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720]/best',
        '480p': 'bestvideo[height<=480][ext=mp4]+bestaudio[ext=m4a]/best[height<=480]/best',
    }

    if preset_id == 'audio_only':
        return download_video(url, output_dir, audio_only=True,
                              progress_callback=progress_callback)

    fmt = format_map.get(preset_id, format_map['best_video'])

    def _progress_hook(d):
        if progress_callback is None:
            return
        status = d.get('status', '')
        if status == 'downloading':
            total = d.get('total_bytes') or d.get('total_bytes_estimate') or 0
            downloaded = d.get('downloaded_bytes', 0)
            speed = d.get('speed', 0)
            eta = d.get('eta', 0)
            pct = (downloaded / total * 100) if total > 0 else 0
            speed_str = ""
            if speed:
                if speed > 1024 * 1024:
                    speed_str = f"{speed / 1024 / 1024:.1f} MB/s"
                elif speed > 1024:
                    speed_str = f"{speed / 1024:.1f} KB/s"
            eta_str = ""
            if eta:
                mins, secs = divmod(int(eta), 60)
                eta_str = f"{mins}:{secs:02d}" if mins else f"{secs}s"
            progress_callback(json.dumps({
                'status': 'downloading',
                'percent': pct,
                'speed': speed_str,
                'eta': eta_str,
            }))
        elif status == 'finished':
            progress_callback(json.dumps({
                'status': 'merging',
                'percent': 100,
                'speed': '',
                'eta': '',
            }))

    os.makedirs(output_dir, exist_ok=True)

    ydl_opts = {
        'format': fmt,
        'outtmpl': os.path.join(output_dir, '%(title)s.%(ext)s'),
        'progress_hooks': [_progress_hook],
        'quiet': True,
        'no_warnings': True,
        'merge_output_format': 'mp4',
    }

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)
            filename = ydl.prepare_filename(info)
            return json.dumps({
                'success': True,
                'filename': filename,
                'title': info.get('title', 'Unknown'),
            })
    except Exception as e:
        return json.dumps({
            'success': False,
            'error': str(e),
        })
