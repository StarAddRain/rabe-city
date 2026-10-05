# Cabin illustration assets

Generated with the built-in `image_gen` tool. Both images are 1672 × 941 pixels.

- `cabin-male.webp`: male driver; 130,348 bytes.
- `cabin-female.webp`: female driver; 124,354 bytes.
- `cabin-male.svg` and `cabin-female.svg`: compatibility wrappers embedding the respective WebP as a data URI. These contain the same generated raster images; they are not vector replacements. The wrappers support the existing Java static server's SVG content type.

The central touchscreen's blank inner corners, measured visually in image coordinates, are approximately `(941,410)`, `(1361,422)`, `(1356,651)`, `(920,622)` clockwise from the top left. A safe rectangular UI region is `left:58%; top:48%; width:21%; height:15%`.

## Male generation prompt

Use case: stylized-concept. Asset type: production raster illustration background for an interactive in-car web panel. Create ONE wide 16:9 illustration, camera looking forward from the rear-right passenger seat inside a left-hand-drive modern electric car. Soft refined cartoon 3D/digital illustration with sage green, warm cream, charcoal forest green, muted ochre colors matching a tranquil isometric city game. A young adult male driver with short dark brown hair, pale warm skin and soft cream long-sleeve clothing sits correctly IN THE LEFT DRIVER SEAT in the left 40% of frame, seat belt fastened, both hands naturally holding the steering wheel. Visible left side profile, calm expression, gaze towards road, no typing. The interior is Tesla-like minimal with a single horizontal landscape central touchscreen and no instrument cluster. Place the ONLY display with pure uniform pale ivory blank screen in a nearly front-facing straight rectangular form at x=52% to 88%, y=58% to 88% of the image; thin rounded dark green bezel. The blank display must be unobstructed for later HTML overlay. Dashboard fills lower area, front windshield with a serene stylized sage-green city street, trees and low buildings fills upper half. Clean coherent interior perspective, comfortable premium cabin. No labels, no text anywhere, no icons, no microphone graphic yet, no UI overlay cards, no borders, no laptops, no keyboard, no extra screens, no rear passengers, no watermarks.

## Female edit prompt

Input: generated male image, used as the edit target.

Use case: precise-object-edit. Asset type: alternate driver image for the identical interactive in-car web illustration. Edit the supplied image by changing ONLY the male driver into an adult woman with dark brown hair in a simple low ponytail, soft natural side profile, a muted sage green long-sleeve top, and the same correctly fastened seat belt. The woman must sit in the EXACT same left DRIVER seat and have both hands holding the same steering wheel, looking ahead at the road, not typing. Preserve the image width/height and framing EXACTLY. Keep all car interior, camera viewpoint, dashboard, seats, windshield city scene and colors unchanged. Most importantly preserve the single blank ivory central landscape touchscreen in EXACTLY the same position, size, proportions, bezel, angle and untouched blank appearance for HTML overlay coordinates shared with the original. No text or UI, no keyboard, no laptop, no other screen, no watermark. Keep the same high quality soft stylized 3D illustration style.
