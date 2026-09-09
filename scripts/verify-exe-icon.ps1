[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$ExecutablePath
)

$resolvedPath = (Resolve-Path -LiteralPath $ExecutablePath -ErrorAction Stop).Path

Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;

public static class LocalDropNativeResources {
    public delegate bool EnumResNameProc(IntPtr module, IntPtr type, IntPtr name, IntPtr parameter);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern IntPtr LoadLibraryEx(string fileName, IntPtr file, uint flags);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool FreeLibrary(IntPtr module);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern bool EnumResourceNames(IntPtr module, IntPtr type, EnumResNameProc callback, IntPtr parameter);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern IntPtr FindResource(IntPtr module, IntPtr name, IntPtr type);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern uint SizeofResource(IntPtr module, IntPtr resource);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern IntPtr LoadResource(IntPtr module, IntPtr resource);

    [DllImport("kernel32.dll", SetLastError = true)]
    public static extern IntPtr LockResource(IntPtr resourceData);
}
'@

$loadLibraryAsDataFile = 0x00000002
$module = [LocalDropNativeResources]::LoadLibraryEx($resolvedPath, [IntPtr]::Zero, $loadLibraryAsDataFile)
if ($module -eq [IntPtr]::Zero) {
    throw "Cannot open $resolvedPath as a Win32 resource module. Win32 error: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
}

try {
    $resourceCounts = @{ GroupIcon = 0; Icon = 0 }
    $groupIconNames = [System.Collections.Generic.List[IntPtr]]::new()
    $countResourceNames = {
        param([IntPtr]$moduleHandle, [IntPtr]$resourceType, [IntPtr]$resourceName, [IntPtr]$parameter)
        if ($resourceType.ToInt64() -eq 14) {
            $resourceCounts.GroupIcon++
            $groupIconNames.Add($resourceName)
        }
        elseif ($resourceType.ToInt64() -eq 3) {
            $resourceCounts.Icon++
        }
        return $true
    }

    $callback = [LocalDropNativeResources+EnumResNameProc]$countResourceNames
    foreach ($resourceType in 14, 3) {
        if (-not [LocalDropNativeResources]::EnumResourceNames($module, [IntPtr]$resourceType, $callback, [IntPtr]::Zero)) {
            throw "Cannot enumerate Win32 resource type $resourceType. Win32 error: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
        }
    }
    if ($resourceCounts.GroupIcon -lt 1 -or $resourceCounts.Icon -lt 1) {
        throw "Final EXE is missing required icon resources: RT_GROUP_ICON=$($resourceCounts.GroupIcon), RT_ICON=$($resourceCounts.Icon)."
    }

    $embeddedSizes = [System.Collections.Generic.HashSet[int]]::new()
    foreach ($groupIconName in $groupIconNames) {
        $groupResource = [LocalDropNativeResources]::FindResource($module, $groupIconName, [IntPtr]14)
        if ($groupResource -eq [IntPtr]::Zero) {
            throw "Cannot open RT_GROUP_ICON resource. Win32 error: $([Runtime.InteropServices.Marshal]::GetLastWin32Error())"
        }
        $groupSize = [LocalDropNativeResources]::SizeofResource($module, $groupResource)
        $groupData = [LocalDropNativeResources]::LockResource([LocalDropNativeResources]::LoadResource($module, $groupResource))
        if ($groupData -eq [IntPtr]::Zero -or $groupSize -lt 6) {
            throw 'RT_GROUP_ICON resource is missing or malformed.'
        }
        $bytes = New-Object byte[] $groupSize
        [Runtime.InteropServices.Marshal]::Copy($groupData, $bytes, 0, $groupSize)
        $entryCount = [BitConverter]::ToUInt16($bytes, 4)
        if ($bytes.Length -lt 6 + ($entryCount * 14)) {
            throw 'RT_GROUP_ICON resource has a truncated directory.'
        }
        for ($index = 0; $index -lt $entryCount; $index++) {
            $offset = 6 + ($index * 14)
            $width = if ($bytes[$offset] -eq 0) { 256 } else { [int]$bytes[$offset] }
            $height = if ($bytes[$offset + 1] -eq 0) { 256 } else { [int]$bytes[$offset + 1] }
            if ($width -ne $height) {
                throw "RT_GROUP_ICON contains a non-square icon layer ${width}x${height}."
            }
            [void]$embeddedSizes.Add($width)
        }
    }
    $requiredSizes = @(16, 32, 48, 64, 128, 256)
    foreach ($requiredSize in $requiredSizes) {
        if (-not $embeddedSizes.Contains($requiredSize)) {
            $availableSizes = @($embeddedSizes | Sort-Object) -join ', '
            throw "Final EXE is missing required ${requiredSize}x${requiredSize} icon layer. Found: $availableSizes."
        }
    }
    $availableSizes = @($embeddedSizes | Sort-Object) -join ', '
    Write-Output "Verified Win32 icon resources: RT_GROUP_ICON=$($resourceCounts.GroupIcon), RT_ICON=$($resourceCounts.Icon), layers=$availableSizes"
}
finally {
    [void][LocalDropNativeResources]::FreeLibrary($module)
}
