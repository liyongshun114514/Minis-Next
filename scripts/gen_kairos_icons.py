#!/usr/bin/env python3
"""Rebuild Kairos launcher resources from the maintained source artwork (Pillow/numpy)."""
from pathlib import Path
from PIL import Image
import numpy as np
root=Path(__file__).resolve().parents[1]; res=root/'src/android/app/src/main/res'
source=root/'branding/kairos/hourglass-source.png'
asset=root/'branding/kairos'; asset.mkdir(parents=True,exist_ok=True)
im=Image.open(source).convert('RGB'); a=np.array(im).astype(float); bright=np.max(a,axis=2); mask=np.clip((bright-48)/150,0,1)
# Keep the ivory/amber hourglass silhouette; remove the dark source background.
a[:,:,0]=np.where(mask>0,a[:,:,0],0); fg=Image.fromarray(np.dstack([a.astype('uint8'),(mask*255).astype('uint8')]),'RGBA')
light=np.array(fg).astype(float)
# Soft luminance/chroma blend avoids jagged threshold seams on the ivory ribbon.
neutral=np.clip((np.min(light[:,:,:3],axis=2)-120)/115,0,1)[:,:,None]
light[:,:,:3]=light[:,:,:3]*(1-neutral)+np.array([50,43,105])*neutral
fg_light=Image.fromarray(light.astype('uint8'),'RGBA')
bg_dark='#0B1024'; bg_light='#EFF3FF'
for density,size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
 for prefix,isnight in [('mipmap-',False),('mipmap-night-',True)]:
  d=res/(prefix+density);d.mkdir(exist_ok=True)
  for name,art in [('ic_launcher_foreground',fg),('ic_launcher_foreground_dark',fg),('ic_launcher_foreground_light',fg_light)]:
   if isnight and name!='ic_launcher_foreground':continue
   art.resize((round(size*108/48),)*2,Image.Resampling.LANCZOS).save(d/(name+'.png'))
  canvas=Image.new('RGBA',(size,size),bg_dark);canvas.alpha_composite(fg.resize((size,size),Image.Resampling.LANCZOS));canvas.convert('RGB').save(d/'ic_launcher.png')
for q in ['values','values-night']:
 p=res/q/'ic_launcher_background.xml';p.write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources><color name="ic_launcher_background">'+bg_dark+'</color></resources>\n')
(res/'values/ic_launcher_alt_colors.xml').write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources><color name="ic_launcher_bg_force_light">'+bg_light+'</color><color name="ic_launcher_bg_force_dark">'+bg_dark+'</color></resources>\n')
for q in ['mipmap-anydpi-v26','mipmap-night-anydpi-v26']:
 for name,fore,bg in [('ic_launcher','ic_launcher_foreground','ic_launcher_background'),('ic_launcher_classic_light','ic_launcher_foreground_light','ic_launcher_bg_force_light'),('ic_launcher_classic_dark','ic_launcher_foreground_dark','ic_launcher_bg_force_dark')]:
  p=res/q/(name+'.xml');p.write_text('<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@color/'+bg+'" />\n    <foreground android:drawable="@mipmap/'+fore+'" />\n    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>\n')
# Themed-icon silhouette follows the SAME artwork (not an unrelated old vector).
# 108 dp PNG alpha is supplied through the adaptive-icon monochrome drawable.
mono=Image.new('RGBA',fg.size,'white'); mono.putalpha(fg.getchannel('A'))
mono_dir=res/'drawable-nodpi'; mono_dir.mkdir(exist_ok=True)
mono.resize((432,432),Image.Resampling.LANCZOS).save(mono_dir/'ic_launcher_monochrome_mask.png')
(res/'drawable/ic_launcher_monochrome.xml').write_text('<?xml version="1.0" encoding="utf-8"?>\n<bitmap xmlns:android="http://schemas.android.com/apk/res/android" android:src="@drawable/ic_launcher_monochrome_mask" android:gravity="fill" android:filter="true" />\n')
preview=Image.new('RGB',(768,384),'#182036')
for i,(art,bg) in enumerate([(fg,bg_dark),(fg_light,bg_light)]):
 tile=Image.new('RGBA',(384,384),bg);tile.alpha_composite(art.resize((384,384),Image.Resampling.LANCZOS));preview.paste(tile.convert('RGB'),(i*384,0))
preview.save(asset/'icons-preview.png')
print('Generated 5 densities; auto/dark/light adaptive icons and monochrome; source retained branding/kairos')
