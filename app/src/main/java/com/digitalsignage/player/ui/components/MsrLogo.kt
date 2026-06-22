package com.digitalsignage.player.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.digitalsignage.player.R
import com.digitalsignage.player.ui.theme.Dimens

@Composable
fun MsrLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(id = R.drawable.ic_launcher_foreground),
        contentDescription = "MSR Digital Signage",
        modifier = modifier.size(Dimens.LogoSize)
    )
}
