#!/usr/bin/env python3
"""Rebuild Kairos launcher resources from the maintained source artwork (Pillow/numpy)."""
from pathlib import Path
import math, shutil
from PIL import Image
import numpy as np
root=Path(__file__).resolve().parents[1]; res=root/'src/android/app/src/main/res'
source=root/'branding/kairos/star-ring-source.png'
asset=root/'branding/kairos'; asset.mkdir(parents=True,exist_ok=True)
im=Image.open(source).convert('RGB'); a=np.array(im).astype(float); bright=np.max(a,axis=2); mask=np.clip((bright-48)/150,0,1)
# Keep the generated star/ring silhouette; remove the solid background for adaptive layers.
a[:,:,0]=np.where(mask>0,a[:,:,0],0); fg=Image.fromarray(np.dstack([a.astype('uint8'),(mask*255).astype('uint8')]),'RGBA')
light=np.array(fg); white=(light[:,:,0]>185)&(light[:,:,1]>185)&(light[:,:,2]>185); light[white,:3]=[50,43,105];fg_light=Image.fromarray(light,'RGBA')
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
# Single-colour vector shares the central star/orbit silhouette and fits circular masks.
pts=[]
for i in range(97):
 t=2*math.pi*i/96;x=25*math.cos(t);y=10*math.sin(t);th=-math.pi/4
 pts.append((54+x*math.cos(th)-y*math.sin(th),54+x*math.sin(th)+y*math.cos(th)))
path='M '+' L '.join(f'{x:.3f},{y:.3f}' for x,y in pts)+' Z'
(res/'drawable/ic_launcher_monochrome.xml').write_text('<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n<path android:strokeColor="#FFFFFFFF" android:strokeWidth="4" android:fillColor="#00000000" android:pathData="'+path+'"/>\n<path android:fillColor="#FFFFFFFF" android:pathData="M54,37 Q57,51 71,54 Q57,57 54,71 Q51,57 37,54 Q51,51 54,37 Z"/>\n</vector>\n')
preview=Image.new('RGB',(768,384),'#182036')
for i,(art,bg) in enumerate([(fg,bg_dark),(fg_light,bg_light)]):
 tile=Image.new('RGBA',(384,384),bg);tile.alpha_composite(art.resize((384,384),Image.Resampling.LANCZOS));preview.paste(tile.convert('RGB'),(i*384,0))
preview.save(asset/'icons-preview.png')
print('Generated 5 densities; auto/dark/light adaptive icons and monochrome; source retained branding/kairos')
