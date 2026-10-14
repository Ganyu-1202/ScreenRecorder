#define _WIN32_WINNT 0x0A00
#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <functiondiscoverykeys_devpkey.h>
#include <stdio.h>
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <algorithm>
#include <stdint.h>
static void check(HRESULT hr) { if(FAILED(hr)) { fprintf(stderr,"WASAPI error 0x%08lx\n",(unsigned long)hr); ExitProcess(2); } }
static std::string utf8(const wchar_t* v) { int n=WideCharToMultiByte(CP_UTF8,0,v,-1,0,0,0,0); std::string s(n,0); WideCharToMultiByte(CP_UTF8,0,v,-1,s.data(),n,0,0); s.pop_back(); return s; }
static bool writeAll(HANDLE h,const void* data,DWORD size) { const BYTE* p=(const BYTE*)data; while(size) { DWORD n; if(!WriteFile(h,p,size,&n,0)||!n)return false; p+=n;size-=n; } return true; }
static std::atomic<int> state(0);
int wmain(int argc,wchar_t** argv) {
 check(CoInitializeEx(NULL,COINIT_MULTITHREADED));
 IMMDeviceEnumerator* en=nullptr; check(CoCreateInstance(__uuidof(MMDeviceEnumerator),NULL,CLSCTX_ALL,__uuidof(IMMDeviceEnumerator),(void**)&en));
 if(argc==2 && wcscmp(argv[1],L"--list")==0) {
  IMMDeviceCollection* ds=nullptr; check(en->EnumAudioEndpoints(eRender,DEVICE_STATE_ACTIVE,&ds)); UINT n; check(ds->GetCount(&n));
  for(UINT i=0;i<n;i++) { IMMDevice* d; check(ds->Item(i,&d)); LPWSTR id; check(d->GetId(&id)); IPropertyStore* p; check(d->OpenPropertyStore(STGM_READ,&p)); PROPVARIANT v; PropVariantInit(&v); check(p->GetValue(PKEY_Device_FriendlyName,&v));
   printf("%s\t%s\n",utf8(id).c_str(),v.vt==VT_LPWSTR?utf8(v.pwszVal).c_str():"Playback device");
   PropVariantClear(&v);p->Release();CoTaskMemFree(id);d->Release(); }
  ds->Release();en->Release();CoUninitialize();return 0;
 }
 if(argc!=3) return 1;
 IMMDevice* device=nullptr; check(en->GetDevice(argv[1],&device));
 IAudioClient* client=nullptr; check(device->Activate(__uuidof(IAudioClient),CLSCTX_ALL,NULL,(void**)&client));
 WAVEFORMATEX* mix=nullptr; check(client->GetMixFormat(&mix));
 // Keep the endpoint engine running during silence so the capture clock never stalls.
 IAudioClient* render=nullptr; check(device->Activate(__uuidof(IAudioClient),CLSCTX_ALL,NULL,(void**)&render));
 check(render->Initialize(AUDCLNT_SHAREMODE_SHARED,0,1000000,0,mix,NULL));
 IAudioRenderClient* output=nullptr; check(render->GetService(__uuidof(IAudioRenderClient),(void**)&output)); UINT capacity;check(render->GetBufferSize(&capacity));
 BYTE* silence;check(output->GetBuffer(capacity,&silence));check(output->ReleaseBuffer(capacity,AUDCLNT_BUFFERFLAGS_SILENT));
 check(client->Initialize(AUDCLNT_SHAREMODE_SHARED,AUDCLNT_STREAMFLAGS_LOOPBACK,1000000,0,mix,NULL));
 IAudioCaptureClient* capture=nullptr;check(client->GetService(__uuidof(IAudioCaptureClient),(void**)&capture));
 HANDLE pipe=CreateNamedPipeW(argv[2],PIPE_ACCESS_OUTBOUND,PIPE_TYPE_BYTE|PIPE_WAIT|PIPE_REJECT_REMOTE_CLIENTS,1,8192,8192,0,NULL);
 if(pipe==INVALID_HANDLE_VALUE)return 3;
 puts("READY");fflush(stdout);
 std::thread([] { int c;while((c=getchar())!=EOF) { if(c=='p')state=1; if(c=='r')state=0; if(c=='q')ExitProcess(0); } ExitProcess(0); }).detach();
 if(!ConnectNamedPipe(pipe,NULL)&&GetLastError()!=ERROR_PIPE_CONNECTED)return 4;
 // A streaming WAVE header preserves the endpoint's native sample rate/channels/format.
 DWORD fmtSize=sizeof(WAVEFORMATEX)+mix->cbSize, riffSize=0xffffffff, dataSize=0xffffffff;
 if(!writeAll(pipe,"RIFF",4)||!writeAll(pipe,&riffSize,4)||!writeAll(pipe,"WAVEfmt ",8)||!writeAll(pipe,&fmtSize,4)||!writeAll(pipe,mix,fmtSize)||!writeAll(pipe,"data",4)||!writeAll(pipe,&dataSize,4))return 5;
 check(render->Start());check(client->Start());bool running=true;
 while(running) {
  UINT padding;check(render->GetCurrentPadding(&padding)); if(padding<capacity) { check(output->GetBuffer(capacity-padding,&silence));check(output->ReleaseBuffer(capacity-padding,AUDCLNT_BUFFERFLAGS_SILENT)); }
  UINT packets;check(capture->GetNextPacketSize(&packets));
  while(packets) { BYTE* data;UINT frames;DWORD flags;check(capture->GetBuffer(&data,&frames,&flags,NULL,NULL));
   if(!state.load()) { DWORD bytes=frames*mix->nBlockAlign; std::vector<BYTE> zeros; if(flags&AUDCLNT_BUFFERFLAGS_SILENT) { zeros.resize(bytes);data=zeros.data(); } running=writeAll(pipe,data,bytes); }
   check(capture->ReleaseBuffer(frames));if(!running)break;check(capture->GetNextPacketSize(&packets));
  }
  Sleep(5);
 }
 client->Stop();render->Stop();CloseHandle(pipe);capture->Release();output->Release();client->Release();render->Release();CoTaskMemFree(mix);device->Release();en->Release();CoUninitialize();return 0;
}
