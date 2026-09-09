import {test,expect} from '@playwright/test';
async function fakeCamera(page){await page.addInitScript(()=>{
  window.__commands=[];let id=10;
  class FakeSocket {static OPEN=1;readyState=1;constructor(){setTimeout(()=>{this.onmessage?.({data:JSON.stringify({type:'ready',protocol:1})});},30);}
    send(text){const message=JSON.parse(text);window.__commands.push(message);
      if(message.type==='pull'){const c=document.createElement('canvas');c.width=720;c.height=1280;const x=c.getContext('2d');x.fillStyle='#253747';x.fillRect(0,0,720,1280);x.strokeStyle='#8aa3b4';x.lineWidth=3;x.strokeRect(140,390,440,310);x.fillStyle='#afc4d4';x.font='24px sans-serif';x.fillText('CAMERA TEST FIXTURE',180,550);const binary=atob(c.toDataURL('image/jpeg').split(',')[1]),jpeg=Uint8Array.from(binary,v=>v.charCodeAt(0));const meta=new TextEncoder().encode(JSON.stringify({type:'frame',frameId:String(id++),width:720,height:1280,tracking:true,count:0,depthPoints:5000,annotations:[]}));const b=new Uint8Array(4+meta.length+jpeg.length);new DataView(b.buffer).setUint32(0,meta.length);b.set(meta,4);b.set(jpeg,4+meta.length);setTimeout(()=>this.onmessage?.({data:b.buffer}),10);}
      if(message.type==='draw')setTimeout(()=>this.onmessage?.({data:JSON.stringify({type:'ack',requestId:message.requestId,annotationId:'mark-1',count:1})}),30);
    }close(){this.readyState=3;}}
  window.WebSocket=FakeSocket;
});}
test('invitation landing page is usable without installing an app',async({page},testInfo)=>{await page.goto('/');await expect(page.getByRole('heading',{name:/See what they see/})).toBeVisible();await expect(page.locator('#joinMessage')).toContainText('complete invitation');await page.screenshot({path:`test-results/${testInfo.project.name}-landing.png`,fullPage:true});});
test('pin references the exact held camera frame',async({page},testInfo)=>{await fakeCamera(page);await page.goto('/#token=test');await page.getByRole('button',{name:/Join camera session/}).click();await expect(page.locator('#liveBadge')).toHaveText('LIVE');
  const box=await page.locator('#scene').boundingBox();await page.mouse.click(box.x+box.width/2,box.y+box.height/2);
  await expect.poll(()=>page.evaluate(()=>window.__commands.some(x=>x.type==='draw'))).toBeTruthy();
  const commands=await page.evaluate(()=>window.__commands);const draw=commands.find(x=>x.type==='draw');const hold=commands.find(x=>x.type==='hold'&&x.frameId===draw.frameId);expect(hold).toBeTruthy();expect(draw.points[0][0]).toBeCloseTo(.5,1);expect(draw.points[0][1]).toBeCloseTo(.5,1);
  await page.screenshot({path:`test-results/${testInfo.project.name}-session.png`,fullPage:true});
});
test('freeze is explicit and color selection is accessible',async({page})=>{await fakeCamera(page);await page.goto('/#token=test');await page.getByRole('button',{name:/Join camera session/}).click();await expect(page.locator('#liveBadge')).toHaveText('LIVE');await page.getByRole('button',{name:'Amber',exact:true}).click();await expect(page.getByRole('button',{name:'Amber',exact:true})).toHaveAttribute('aria-pressed','true');await page.locator('#freeze').click();await expect(page.locator('#liveBadge')).toHaveText('FRAME HELD');await page.locator('#freeze').click();await expect(page.locator('#liveBadge')).toHaveText('LIVE');});
