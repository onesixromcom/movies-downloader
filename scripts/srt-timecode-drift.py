#!/usr/bin/env python3
"""
SRT Subtitle Timecode Sync Script
Adjusts SRT subtitle timecodes with progressive offset correction
Removes accumulated drift (0.024 seconds per second of runtime)
"""

import re
import sys
from pathlib import Path

def parse_time(time_str):
    """Parse SRT timestamp format (HH:MM:SS,mmm) to seconds"""
    match = re.match(r'(\d{2}):(\d{2}):(\d{2})\,(\d{3})', time_str)
    if not match:
        raise ValueError(f"Invalid time format: {time_str}")
    
    hours, minutes, seconds, milliseconds = map(int, match.groups())
    total_seconds = hours * 3600 + minutes * 60 + seconds + milliseconds / 1000.0
    return total_seconds

def format_time(seconds):
    """Convert seconds back to SRT timestamp format (HH:MM:SS,mmm)"""
    # Ensure we don't go negative
    seconds = max(0, seconds)
    
    hours = int(seconds // 3600)
    minutes = int((seconds % 3600) // 60)
    secs = seconds % 60
    whole_secs = int(secs)
    millisecs = int((secs - whole_secs) * 1000)
    
    return f"{hours:02d}:{minutes:02d}:{whole_secs:02d}.{millisecs:03d}"

def calculate_progressive_offset(original_seconds, drift_per_second=0.024):
    """
    Calculate the accumulated offset that needs to be removed
    
    Args:
        original_seconds: Original timestamp in seconds
        drift_per_second: How much drift accumulates per second (default: 0.024)
    
    Returns:
        Adjusted timestamp with accumulated drift removed
    """
    # The total drift accumulated is: original_time * drift_per_second
    accumulated_drift = original_seconds * drift_per_second
    adjusted_seconds = original_seconds - accumulated_drift
    
    return adjusted_seconds

def adjust_timestamp_progressive(time_str, drift_per_second=0.024):
    """
    Adjust timestamp by removing accumulated drift
    
    Args:
        time_str: Original timestamp string
        drift_per_second: Drift accumulation rate (default: 0.024 for 25fps->23.976fps)
    """
    original_seconds = parse_time(time_str)
    adjusted_seconds = calculate_progressive_offset(original_seconds, drift_per_second)
    return format_time(adjusted_seconds)

def process_SRT_file(input_file, output_file=None, drift_per_second=0.024):
    """
    Process SRT file and adjust all timestamps with progressive offset correction
    
    Args:
        input_file: Path to input SRT file
        output_file: Path to output SRT file (default: input_file with _synced suffix)
        drift_per_second: How much the subtitles drift per second of runtime
    """
    input_path = Path(input_file)
    
    if not input_path.exists():
        raise FileNotFoundError(f"Input file not found: {input_file}")
    
    if output_file is None:
        output_path = input_path.with_stem(f"{input_path.stem}_synced")
    else:
        output_path = Path(output_file)
    
    # Regex pattern to match SRT timestamp lines (start --> end)
    timestamp_pattern = r'(\d{2}:\d{2}:\d{2}\,\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2}\,\d{3})'
    
    try:
        with open(input_path, 'r', encoding='utf-8') as infile:
            content = infile.read()
        
        def replace_timestamps(match):
            start_time = match.group(1)
            end_time = match.group(2)
            
            new_start = adjust_timestamp_progressive(start_time, drift_per_second)
            new_end = adjust_timestamp_progressive(end_time, drift_per_second)
            
            return f"{new_start} --> {new_end}"
        
        # Replace all timestamp lines
        adjusted_content = re.sub(timestamp_pattern, replace_timestamps, content)
        
        # Count how many timestamps were processed
        timestamp_count = len(re.findall(timestamp_pattern, content))
        
        with open(output_path, 'w', encoding='utf-8') as outfile:
            outfile.write(adjusted_content)
        
        print(f"Successfully processed SRT file:")
        print(f"Input:  {input_path}")
        print(f"Output: {output_path}")
        print(f"Processed: {timestamp_count} timestamp pairs")
        print(f"Drift correction: -{drift_per_second:.3f} seconds per second of runtime")
        
        # Show examples of correction at different times
        print(f"\nDrift correction examples:")
        for minutes in [1, 5, 10, 30, 60, 90]:
            seconds = minutes * 60
            correction = seconds * drift_per_second
            print(f"  At {minutes:2d} min: -{correction:6.2f} seconds correction")
        
    except Exception as e:
        print(f"Error processing file: {e}")
        return False
    
    return True

def calculate_drift_from_known_offset(time_minutes, observed_offset_seconds):
    """
    Calculate drift per second from a known offset at a specific time
    
    Args:
        time_minutes: Time point where offset was measured
        observed_offset_seconds: How much the subtitles are off at that time
    
    Returns:
        Calculated drift per second
    """
    time_seconds = time_minutes * 60
    drift_per_second = observed_offset_seconds / time_seconds
    return drift_per_second

def main():
    """Main function with command line argument handling"""
    if len(sys.argv) < 2:
        print("Usage: python SRT_sync.py <input_SRT_file> [output_SRT_file] [drift_per_second]")
        print("   or: python SRT_sync.py <input_SRT_file> --calc-drift <minutes> <offset_seconds>")
        print()
        print("Examples:")
        print("  python SRT_sync.py subtitles.SRT")
        print("  python SRT_sync.py subtitles.SRT synced_subtitles.SRT")
        print("  python SRT_sync.py subtitles.SRT synced.SRT 0.024")
        print("  python SRT_sync.py subtitles.SRT --calc-drift 30 43.2")
        print()
        print("Default drift: 0.024 seconds per second (25fps -> 23.976fps)")
        print("--calc-drift: Calculate drift from known offset at specific time")
        print("              (e.g., 43.2 seconds off after 30 minutes)")
        sys.exit(1)
    
    input_file = sys.argv[1]
    
    # Check if we're calculating drift from known offset
    if len(sys.argv) >= 5 and sys.argv[2] == '--calc-drift':
        try:
            time_minutes = float(sys.argv[3])
            offset_seconds = float(sys.argv[4])
            
            calculated_drift = calculate_drift_from_known_offset(time_minutes, offset_seconds)
            
            print(f"Calculated drift rate:")
            print(f"  Time point: {time_minutes} minutes")
            print(f"  Observed offset: {offset_seconds} seconds")
            print(f"  Calculated drift per second: {calculated_drift:.6f}")
            print(f"  Use this value: python SRT_sync.py {input_file} output.SRT {calculated_drift:.6f}")
            return
            
        except ValueError:
            print("Invalid numbers for drift calculation")
            sys.exit(1)
    
    # Normal processing
    output_file = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] != '--calc-drift' else None
    
    # Handle drift per second
    drift_per_second = 0.024  # Default: 25fps to 23.976fps drift
    if len(sys.argv) > 3 and sys.argv[2] != '--calc-drift':
        try:
            drift_per_second = float(sys.argv[3])
        except ValueError:
            print(f"Invalid drift per second: {sys.argv[3]}")
            sys.exit(1)
    elif len(sys.argv) > 2 and sys.argv[2] != '--calc-drift':
        # If output file is specified but no drift, keep default
        pass
    
    success = process_SRT_file(input_file, output_file, drift_per_second)
    
    if not success:
        sys.exit(1)

if __name__ == "__main__":
    main()
