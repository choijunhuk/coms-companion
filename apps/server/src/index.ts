import {fileURLToPath} from 'node:url';
import {resolve} from 'node:path';
import {buildApp} from './app.ts';

const root=fileURLToPath(new URL('../../../',import.meta.url));
const port=Number(process.env.PORT??'3000');
if(!Number.isInteger(port)||port<1||port>65535)throw new Error('PORT must be a valid integer port.');
if(process.env.TRUST_PROXY&&process.env.TRUST_PROXY!=='loopback')throw new Error('TRUST_PROXY must be loopback when set.');
const app=buildApp({
  adminToken:process.env.ADMIN_TOKEN??'',encryptionKey:process.env.SERVER_ENCRYPTION_KEY??'',
  databasePath:resolve(root,process.env.DATABASE_PATH??'data/companion.sqlite'),
  publicBaseUrl:process.env.PUBLIC_BASE_URL??'http://localhost:3000',
  webDist:resolve(root,'apps/web/dist'),trustProxy:process.env.TRUST_PROXY==='loopback'?'loopback':undefined,
  rosterPath:process.env.ROSTER_PATH?resolve(root,process.env.ROSTER_PATH):undefined
});
try {await app.listen({host:process.env.HOST??'127.0.0.1',port});console.log(`COM’s Companion response server listening on port ${port}`);}
catch(error){console.error(error instanceof Error?error.message:'Server startup failed');await app.close();process.exitCode=1;}
for(const signal of ['SIGINT','SIGTERM'] as const)process.once(signal,()=>{void app.close().then(()=>process.exit(0));});
