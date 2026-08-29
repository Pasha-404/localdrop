#ifndef AppVersion
  #error "AppVersion must be supplied by Gradle."
#endif
#ifndef AppImageDir
  #error "AppImageDir must be supplied by Gradle."
#endif
#ifndef OutputDir
  #error "OutputDir must be supplied by Gradle."
#endif
#ifndef AppId
  #error "AppId must be supplied by Gradle."
#endif
#ifndef IconFile
  #error "IconFile must be supplied by Gradle."
#endif
#ifndef LegacyMsiUpgradeCode
  #error "LegacyMsiUpgradeCode must be supplied by Gradle."
#endif

#define AppName "LocalDrop"
#define Publisher "PashaApps"
#define RepositoryUrl "https://github.com/Pasha-404/localdrop"
#define InstallerAssetName AppName + "-Setup-" + AppVersion + "-x64"

[Setup]
AppId={#AppId}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#Publisher}
AppPublisherURL={#RepositoryUrl}
AppSupportURL={#RepositoryUrl}
AppUpdatesURL={#RepositoryUrl}
DefaultDirName={localappdata}\Programs\{#Publisher}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
DisableDirPage=no
UsePreviousAppDir=yes
UsePreviousTasks=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes
RestartApplications=no
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
OutputDir={#OutputDir}
OutputBaseFilename={#InstallerAssetName}
SetupIconFile={#IconFile}
UninstallDisplayIcon={app}\LocalDrop.exe

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"; Flags: unchecked

[Files]
Source: "{#AppImageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[InstallDelete]
Type: files; Name: "{app}\LocalDrop.exe"
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\icons"

[Icons]
Name: "{group}\LocalDrop"; Filename: "{app}\LocalDrop.exe"
Name: "{autodesktop}\LocalDrop"; Filename: "{app}\LocalDrop.exe"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "SchemaVersion"; ValueData: "1"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "AppId"; ValueData: "{#AppId}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "Name"; ValueData: "LocalDrop"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "TechnicalName"; ValueData: "LocalDrop"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "Version"; ValueData: "{#AppVersion}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "InstallLocation"; ValueData: "{app}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "Executable"; ValueData: "{app}\LocalDrop.exe"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "ProcessName"; ValueData: "LocalDrop.exe"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "Publisher"; ValueData: "{#Publisher}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "RepositoryUrl"; ValueData: "{#RepositoryUrl}"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "InstallerType"; ValueData: "inno"
Root: HKCU; Subkey: "Software\PashaApps\{#AppId}"; ValueType: string; ValueName: "InstalledBy"; ValueData: "installer"

[Run]
Filename: "{app}\LocalDrop.exe"; Description: "Launch LocalDrop"; Flags: nowait postinstall skipifsilent

[Code]
const
  ErrorSuccess = 0;
  LegacyMsiUpgradeCode = '{#LegacyMsiUpgradeCode}';

function MsiEnumRelatedProducts(const UpgradeCode: String; Reserved, ProductIndex: Integer; var ProductCode: String): Integer;
  external 'MsiEnumRelatedProductsW@msi.dll stdcall';

function FindLegacyProductCode(var ProductCode: String): Boolean;
begin
  SetLength(ProductCode, 39);
  Result := MsiEnumRelatedProducts(LegacyMsiUpgradeCode, 0, 0, ProductCode) = ErrorSuccess;
end;

function RemoveLegacyMsiProducts(): Boolean;
var
  ProductCode: String;
  ExitCode: Integer;
begin
  Result := True;
  while FindLegacyProductCode(ProductCode) do begin
    if not Exec(ExpandConstant('{sys}\msiexec.exe'), '/x ' + ProductCode + ' /passive /norestart', '', SW_SHOW, ewWaitUntilTerminated, ExitCode) then begin
      Result := False;
      Exit;
    end;
    if (ExitCode <> 0) and (ExitCode <> 3010) then begin
      Result := False;
      Exit;
    end;
  end;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ProductCode: String;
begin
  Result := '';
  if not FindLegacyProductCode(ProductCode) then begin
    Exit;
  end;

  if (not WizardSilent) and
     (MsgBox('A legacy LocalDrop installation was found. It must be removed before the standardized per-user version can be installed. Continue?', mbConfirmation, MB_YESNO) <> IDYES) then begin
    Result := 'Installation was cancelled because the legacy LocalDrop version was not removed.';
    Exit;
  end;

  if not RemoveLegacyMsiProducts() then begin
    Result := 'The legacy LocalDrop installation could not be removed. Close LocalDrop, uninstall the old version from Windows Settings, and run this installer again.';
  end;
end;
