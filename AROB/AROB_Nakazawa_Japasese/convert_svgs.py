import os
import subprocess
import glob
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image

# Configuration
FIGURES_DIR = Path("/Users/nakazawa/Desktop/Nozawa Lab/RealTime-IBI-BP/AROB/AROB_Nakazawa_Japasese/figures")
CHROME_PATH = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
SCALE_FACTOR = 4.0  # Scale up for higher resolution (e.g. 72dpi * 4 = 288dpi)

def get_svg_dimensions(svg_path):
    """
    Parse SVG file to get width and height in pixels.
    Returns (width, height) as integers, scaled by SCALE_FACTOR.
    """
    try:
        tree = ET.parse(svg_path)
        root = tree.getroot()
        
        width_str = root.get("width")
        height_str = root.get("height")
        viewbox = root.get("viewBox")
        
        w = None
        h = None
        
        if width_str and height_str:
            w = parse_unit(width_str)
            h = parse_unit(height_str)
        elif viewbox:
            parts = viewbox.split()
            if len(parts) == 4:
                w = float(parts[2])
                h = float(parts[3])
        
        if w is None or h is None:
            print(f"  Warning: Could not determine dimensions for {svg_path.name}, using default.")
            return 2000, 2000
            
        # Apply scaling
        return int(w * SCALE_FACTOR), int(h * SCALE_FACTOR)
        
    except Exception as e:
        print(f"  Error parsing SVG {svg_path.name}: {e}")
        return 2000, 2000

def parse_unit(value_str):
    """
    Convert SVG length string to pixels (assuming 72 DPI base for pt).
    """
    if not value_str:
        return 0.0
        
    value_str = value_str.strip().lower()
    match = re.match(r"([\d\.]+)([a-z%]*)", value_str)
    if not match:
        return 0.0
        
    val = float(match.group(1))
    unit = match.group(2)
    
    if unit == "pt":
        return val
    elif unit == "in":
        return val * 72.0
    elif unit == "cm":
        return val * 28.3465
    elif unit == "mm":
        return val * 2.83465
    elif unit == "px" or unit == "":
        return val
    elif unit == "pc":
        return val * 12.0
    else:
        return val

def trim_whitespace(image_path, border=5):
    """
    Trim whitespace from PNG image and save it back.
    border: number of pixels to keep as padding around the content
    """
    try:
        img = Image.open(image_path)
        
        # Convert to RGB if necessary (in case of RGBA)
        if img.mode == 'RGBA':
            # Create a white background
            bg = Image.new('RGB', img.size, (255, 255, 255))
            bg.paste(img, mask=img.split()[3])  # Use alpha channel as mask
            img = bg
        
        # Convert to grayscale for easier processing
        gray = img.convert('L')
        
        # Get bounding box of non-white content
        # Invert the image so that white becomes black
        bbox = gray.point(lambda x: 0 if x > 250 else 255).getbbox()
        
        if bbox:
            # Add border
            bbox = (
                max(0, bbox[0] - border),
                max(0, bbox[1] - border),
                min(img.width, bbox[2] + border),
                min(img.height, bbox[3] + border)
            )
            
            # Crop and save
            img_cropped = img.crop(bbox)
            img_cropped.save(image_path, 'PNG', dpi=(300, 300))
            return True
        else:
            print(f"  Warning: Could not find content bounds for {image_path.name}")
            return False
            
    except Exception as e:
        print(f"  Error trimming {image_path.name}: {e}")
        return False

def convert_svg_to_png(svg_path):
    if not os.path.exists(CHROME_PATH):
        print(f"Error: Chrome not found at {CHROME_PATH}")
        return

    width, height = get_svg_dimensions(svg_path)
    print(f"Converting {svg_path.name} ({width}x{height})...")
    
    # Chrome headless command
    cmd = [
        CHROME_PATH,
        "--headless",
        "--screenshot",
        f"--window-size={width},{height}",
        "--default-background-color=ffffff",
        "--hide-scrollbars",
        f"file://{svg_path.absolute()}"
    ]
    
    try:
        # Run Chrome in the figures directory
        subprocess.run(cmd, check=True, cwd=FIGURES_DIR, capture_output=True)
        
        # Rename screenshot.png to target png name
        screenshot = FIGURES_DIR / "screenshot.png"
        target_png = svg_path.with_suffix(".png")
        
        if screenshot.exists():
            if target_png.exists():
                os.remove(target_png)
            os.rename(screenshot, target_png)
            
            # Trim whitespace from the PNG
            if trim_whitespace(target_png):
                print(f"  -> Created and trimmed {target_png.name}")
            else:
                print(f"  -> Created {target_png.name} (trimming failed)")
        else:
            print(f"  -> Failed: screenshot.png not found")
            
    except subprocess.CalledProcessError as e:
        print(f"  -> Error running Chrome: {e}")

def main():
    if not FIGURES_DIR.exists():
        print(f"Error: Figures directory not found at {FIGURES_DIR}")
        return

    svg_files = list(FIGURES_DIR.glob("*.svg"))
    print(f"Found {len(svg_files)} SVG files in {FIGURES_DIR}")
    print(f"Using scale factor: {SCALE_FACTOR}x for high resolution")
    
    for svg_file in svg_files:
        convert_svg_to_png(svg_file)

if __name__ == "__main__":
    main()
