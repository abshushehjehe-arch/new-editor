package com.example.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.ui.components.BatchExportDialog
import com.example.ui.components.ClipPreviewDialog
import com.example.ui.components.EditClipDialog
import com.example.ui.components.GeminiGuideDialog
import com.example.ui.components.PaddingSettingsDialog
import com.example.ui.localization.UiCopy
import com.example.ui.localization.uiCopyFor
import com.example.ui.screens.ClipEditorScreen
import com.example.ui.screens.ClipsLibraryScreen
import com.example.ui.screens.ProjectsScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.theme.RazorVermilion
import com.example.ui.theme.RazorVermilionContainer
import com.example.ui.theme.StudioBorder
import com.example.ui.theme.StudioCarbon
import com.example.ui.theme.StudioObsidian
import com.example.ui.theme.TimecodeAmber
import com.example.ui.viewmodel.StudioViewModel

const val ROUTE_STUDIO = "studio"
const val ROUTE_CLIPS = "clips"
const val ROUTE_PROJECTS = "projects"

private data class NavTabItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val badgeCount: Int = 0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipSliceApp(
    viewModel: StudioViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val exportedClips by viewModel.exportedClips.collectAsStateWithLifecycle()
    val savedProjects by viewModel.savedProjects.collectAsStateWithLifecycle()
    val copy = uiCopyFor(uiState.language)
    val navController = rememberNavController()

    // Zero-permission SAF picker (supports huge 5–12+ hour video files across Downloads/SD/OTG)
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onVideoSelected(uri)
        }
    }

    // Zero-permission Android Photo Picker (Gallery videos)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.onVideoSelected(uri)
        }
    }

    val navItems = listOf(
        NavTabItem(ROUTE_STUDIO, copy.navStudio, Icons.Rounded.ContentCut),
        NavTabItem(ROUTE_CLIPS, copy.navClips, Icons.Rounded.VideoLibrary, badgeCount = exportedClips.size),
        NavTabItem(ROUTE_PROJECTS, copy.navProjects, Icons.Rounded.History, badgeCount = savedProjects.size)
    )

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(StudioObsidian)
        ) {
            val useNavRail = maxWidth >= 600.dp

            Row(modifier = Modifier.fillMaxSize()) {
                if (useNavRail) {
                    NavigationRail(
                        containerColor = StudioCarbon,
                        modifier = Modifier
                            .fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.statusBars)
                    ) {
                        Spacer(modifier = Modifier.padding(top = 12.dp))
                        navItems.forEach { item ->
                            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(item.route) },
                                icon = { NavTabIcon(item) },
                                label = { Text(item.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = RazorVermilion,
                                    selectedTextColor = RazorVermilion,
                                    indicatorColor = RazorVermilionContainer
                                ),
                                modifier = Modifier.testTag("nav_rail_${item.route}")
                            )
                        }
                    }
                }

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        StudioTopHeader(
                            copy = copy,
                            onToggleLanguage = viewModel::toggleLanguage,
                            onOpenGuide = { viewModel.setShowGuideDialog(true) }
                        )
                    },
                    bottomBar = {
                        if (!useNavRail) {
                            NavigationBar(
                                containerColor = StudioCarbon,
                                tonalElevation = 0.dp
                            ) {
                                navItems.forEach { item ->
                                    val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { navigateToTab(item.route) },
                                        icon = { NavTabIcon(item) },
                                        label = { Text(item.label, style = MaterialTheme.typography.labelMedium) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = RazorVermilion,
                                            selectedTextColor = RazorVermilion,
                                            indicatorColor = RazorVermilionContainer,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        modifier = Modifier.testTag("nav_bar_${item.route}")
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_STUDIO,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        composable(ROUTE_STUDIO) {
                            StudioScreen(
                                state = uiState,
                                copy = copy,
                                onSelectSourceTab = viewModel::selectSourceTab,
                                onLinkUrlChanged = viewModel::onLinkUrlChanged,
                                onResolveLink = viewModel::resolveVideoLink,
                                onClearLink = viewModel::clearResolvedLink,
                                onPickVideoDocument = {
                                    openDocumentLauncher.launch(arrayOf("video/*"))
                                },
                                onPickVideoGallery = {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                    )
                                },
                                onPromptTextChanged = viewModel::onPromptTextChanged,
                                onLoadSamplePrompt = viewModel::loadSamplePrompt,
                                onClearPrompt = viewModel::clearPrompt,
                                onSelectSingleMode = viewModel::setSingleTimestampMode,
                                onUpdateExportSettings = viewModel::updateDefaultExportSettings,
                                onToggleClipSelection = viewModel::toggleClipSelection,
                                onSelectAllClips = viewModel::selectAllClips,
                                onAddManualClip = viewModel::openAddManualClipDialog,
                                onEditClipTime = viewModel::openEditClipDialog,
                                onOpenClipCaptionEditor = viewModel::openClipEditorForParsedClip,
                                onDeleteClip = viewModel::removeClip,
                                onPreviewClip = viewModel::openPreview,
                                onHighlightClip = viewModel::highlightClip,
                                onOpenPaddingSettings = { viewModel.setShowPaddingDialog(true) },
                                onStartBatchExport = viewModel::startBatchExport,
                                getClipCaptionCount = { clip -> viewModel.getCuesForParsedClip(clip).size },
                                loadThumbnail = viewModel::loadFrameThumbnail
                            )
                        }

                        composable(ROUTE_CLIPS) {
                            ClipsLibraryScreen(
                                clips = exportedClips,
                                copy = copy,
                                onPlayClip = viewModel::openPreview,
                                onEditClip = viewModel::openClipEditorForExportedClip,
                                onDeleteClip = viewModel::deleteExportedClip,
                                loadThumbnail = viewModel::loadFrameThumbnail
                            )
                        }

                        composable(ROUTE_PROJECTS) {
                            ProjectsScreen(
                                projects = savedProjects,
                                copy = copy,
                                onLoadProject = { project ->
                                    viewModel.loadSavedProject(project)
                                    navigateToTab(ROUTE_STUDIO)
                                },
                                onDeleteProject = viewModel::deleteProject
                            )
                        }
                    }
                }
            }
        }

        // Full-Screen 9:16 HD & Captions Editor Overlay
        uiState.editorSession?.let { session ->
            ClipEditorScreen(
                session = session,
                language = uiState.language,
                copy = copy,
                onClose = viewModel::closeClipEditor,
                onUpdateSettings = viewModel::updateEditorSettings,
                onImportSubtitleText = viewModel::importSubtitleTextIntoEditor,
                onImportSubtitleFile = viewModel::importSubtitleFileUri,
                onAddOrUpdateCue = viewModel::addOrUpdateEditorCue,
                onAppendSpokenText = viewModel::appendSpokenTextAtPosition,
                onDeleteCue = viewModel::deleteEditorCue,
                onTriggerAiAutoCaption = viewModel::triggerAiAutoCaptionPlaceholder,
                onExportHdClip = viewModel::exportSingleEditedClip
            )
        }
    }

    // Modal Overlays
    uiState.editingClip?.let { editing ->
        EditClipDialog(
            clip = editing,
            copy = copy,
            onDismiss = viewModel::dismissEditClipDialog,
            onSave = viewModel::saveEditedClip
        )
    }

    uiState.previewingClip?.let { previewTarget ->
        ClipPreviewDialog(
            target = previewTarget,
            copy = copy,
            onDismiss = viewModel::closePreview
        )
    }

    BatchExportDialog(
        state = uiState.exportState,
        copy = copy,
        onCancel = viewModel::cancelExport,
        onDismiss = viewModel::dismissExportSheet,
        onOpenClipsTab = { navigateToTab(ROUTE_CLIPS) }
    )

    if (uiState.showPaddingDialog) {
        PaddingSettingsDialog(
            currentPaddingSeconds = uiState.paddingSeconds,
            currentSettings = uiState.defaultEditSettings,
            copy = copy,
            onSelectPadding = viewModel::setPaddingSeconds,
            onUpdateSettings = viewModel::updateDefaultExportSettings,
            onDismiss = { viewModel.setShowPaddingDialog(false) }
        )
    }

    if (uiState.showGuideDialog) {
        GeminiGuideDialog(
            copy = copy,
            onDismiss = { viewModel.setShowGuideDialog(false) }
        )
    }
}

@Composable
private fun NavTabIcon(item: NavTabItem) {
    if (item.badgeCount > 0) {
        BadgedBox(
            badge = {
                Badge(
                    containerColor = TimecodeAmber,
                    contentColor = Color(0xFF1F1600)
                ) {
                    Text(item.badgeCount.coerceAtMost(99).toString())
                }
            }
        ) {
            Icon(imageVector = item.icon, contentDescription = item.label)
        }
    } else {
        Icon(imageVector = item.icon, contentDescription = item.label)
    }
}

@Composable
private fun StudioTopHeader(
    copy: UiCopy,
    onToggleLanguage: () -> Unit,
    onOpenGuide: () -> Unit
) {
    Surface(
        color = StudioCarbon,
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 0.5.dp, color = StudioBorder.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_clipslice_art),
                    contentDescription = stringResource(R.string.content_desc_app_icon),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, StudioBorder, RoundedCornerShape(10.dp))
                )
                Column {
                    Text(
                        text = copy.appTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = copy.appTagline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                TextButton(
                    onClick = onToggleLanguage,
                    modifier = Modifier.testTag("toggle_language_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Translate,
                        contentDescription = stringResource(R.string.content_desc_language),
                        tint = TimecodeAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = copy.langToggleLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = TimecodeAmber
                    )
                }
                IconButton(
                    onClick = onOpenGuide,
                    modifier = Modifier.testTag("open_gemini_guide_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.HelpOutline,
                        contentDescription = stringResource(R.string.content_desc_help),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
