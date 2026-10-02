const {chromium}=require('playwright');const path=require('path');const fs=require('fs');
const OUT=process.argv[2];(async()=>{const b=await chromium.launch({executablePath:'/opt/pw-browsers/chromium'}).catch(()=>chromium.launch());
const p=await b.newPage({viewport:{width:1200,height:360}});
for(const th of ['crescent-night','full-moon','dawn-glow','golden-midnight'])for(const part of ['header','footer']){
 await p.goto('file://'+path.join(__dirname,'gen.html')+`?theme=${th}&part=${part}`);await p.waitForTimeout(150);
 const d=path.join(OUT,th);fs.mkdirSync(d,{recursive:true});
 await (await p.$('svg')).screenshot({path:path.join(d,part+'.jpg'),type:'jpeg',quality:86});}
await b.close()})();
