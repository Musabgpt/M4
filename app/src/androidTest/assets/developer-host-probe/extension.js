
exports.activate = async context => {
 const vscode = require('vscode'), fs = require('fs');
 const target = '/root/projects/M4/m4-language-probe.ts';
 try {
  fs.writeFileSync(target, 'const person = { name: "M4", age: 1 };\nperson.');
  await vscode.extensions.getExtension('vscode.typescript-language-features').activate();
  const doc = await vscode.workspace.openTextDocument(target);
  await vscode.window.showTextDocument(doc);
  for(let i=0;i<60;i++) {
   const result=await vscode.commands.executeCommand('vscode.executeCompletionItemProvider', doc.uri, new vscode.Position(1,7));
   const items = (result?.items || []).map(x=>({label: typeof x.label==='string'?x.label:x.label.label, kind:x.kind}));
   if(items.some(x=>x.label==='name') && items.some(x=>x.label==='age')) {
    fs.writeFileSync('/tmp/m4-language-receipt.json', JSON.stringify({version:vscode.version,items}));
    vscode.window.showInformationMessage('M4 extension and TypeScript completion verified'); return;
   }
   await new Promise(r=>setTimeout(r,1000));
  }
  throw Error('No typed completion result');
 } catch(e) { fs.writeFileSync('/tmp/m4-language-receipt.json', JSON.stringify({error:String(e)})); }
};
